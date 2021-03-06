package se.jsannemo.spooky.compiler.typecheck;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.errorprone.annotations.CheckReturnValue;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import se.jsannemo.spooky.compiler.Errors;
import se.jsannemo.spooky.compiler.Prog;
import se.jsannemo.spooky.compiler.ast.Ast;

/** A type-checker for Spooky {@link se.jsannemo.spooky.compiler.ast.Ast.Program}s. */
public final class TypeChecker {

  private static final Prog.Expr ERROR_EXPR = Prog.Expr.newBuilder().setType(Types.ERROR).build();
  private final boolean permissive;
  private final Errors err;

  private final HashMap<String, FuncData> externs = new HashMap<>();

  final HashMap<String, StructData> structs = new HashMap<>();
  final HashMap<Integer, StructData> structsIdx = new HashMap<>();

  // Mapping from function name to an index in the funcs list.
  private final HashMap<String, Integer> funcIdx = new HashMap<>();
  private final ArrayList<Prog.Func.Builder> funcs = new ArrayList<>();

  private final Prog.Program.Builder programBuilder = Prog.Program.newBuilder();
  private Scope scope = Scope.root();

  /**
   * Initializes a new type-checker.
   *
   * @param permissive whether the type checker should do a best-effort type check on a broken AST.
   *     Otherwise, an {@link IllegalArgumentException} will be thrown for syntactically invalid
   *     syntax trees.
   */
  private TypeChecker(boolean permissive, Errors err) {
    this.permissive = permissive;
    this.err = err;
  }

  private Prog.Program checkProgram(Ast.Program program) {
    // Structs must be registered first, since function parameters and return types may refer to
    // them.
    registerStructs(program.getStructsList());

    // Next, functions must be registered first, since global variables may reference them during
    // initialization.
    program.getExternsList().forEach(this::registerExtern);
    List<Ast.Func> funcs = registerFunctions(program.getFunctionsList());

    // Next, globals must be registered since function expressions can reference them.
    createGlobalInit(program.getGlobalsList());

    // Finally function bodies can be type checked.
    checkFunctions(funcs);
    return programBuilder.build();
  }

  private void registerStructs(List<Ast.StructDecl> structsList) {
    ImmutableList.Builder<Ast.StructDecl> dedupBuilder = ImmutableList.builder();
    // First register all structs to be able to find them when checking fields.
    for (Ast.StructDecl struct : structsList) {
      if (syntaxError(struct.getName().getName().isEmpty())) {
        continue;
      }
      String name = struct.getName().getName();
      if (Types.hasBuiltin(name)) {
        err.error(struct.getPosition(), "Struct name clashes with builtin");
      } else if (structs.containsKey(name)) {
        err.error(struct.getPosition(), "Struct already defined");
      } else {
        structs.put(name, new StructData());
        dedupBuilder.add(struct);
      }
    }
    ImmutableList<Ast.StructDecl> deduplicated = dedupBuilder.build();
    Optional<List<Ast.StructDecl>> maybeOrdering = Structs.topologicalOrder(deduplicated, err);
    // If we could not find a topological ordering, fall-back to random ordering; this is only used
    // as a guarantee for code generation if the program is valid.
    List<Ast.StructDecl> ordering = maybeOrdering.orElse(deduplicated);
    for (Ast.StructDecl decl : ordering) {
      String name = decl.getName().getName();
      StructData data = structs.get(name);
      data.index = programBuilder.getStructsCount();
      data.type = Types.struct(data.index);
      data.name = name;
      structsIdx.put(data.index, data);
      Prog.Struct.Builder struct = Prog.Struct.newBuilder();
      for (Ast.StructField field : decl.getFieldsList()) {
        if (syntaxError(field.getType().getName().isEmpty())) {
          continue;
        }
        data.fields.put(field.getName().getName(), struct.getFieldsCount());
        Prog.Type fieldType = resolveType(field.getType());
        if (fieldType.hasArray()) {
          if (Types.arraySize(fieldType) == 0) {
            err.error(
                field.getType().getPosition(), "Struct arrays can not have inferred dimensions");
          }
          if (Types.arraySize(fieldType) == Integer.MAX_VALUE) {
            err.error(field.getType().getPosition(), "Array field too large");
          }
        }
        struct.addFields(fieldType);
      }
      programBuilder.addStructs(struct);
    }
  }

  private void registerExtern(Ast.FuncDecl decl) {
    String name = decl.getName().getName();
    if (syntaxError(name.isEmpty())) {
      return;
    }
    if (externs.containsKey(name) || funcIdx.containsKey(name)) {
      err.error(decl.getPosition(), "A function with this name is already defined");
      return;
    }
    externs.put(name, new FuncData(getDeclReturnType(decl), getDeclTypes(decl)));
  }

  private Prog.Type getDeclReturnType(Ast.FuncDecl decl) {
    Prog.Type returnType = decl.hasReturnType() ? resolveType(decl.getReturnType()) : Types.VOID;
    if (!Types.isVoid(returnType) && !Types.isCopyable(returnType)) {
      err.error(decl.getReturnType().getPosition(), "Return type not assignable");
      returnType = Types.ERROR;
    }
    return returnType;
  }

  private List<Ast.Func> registerFunctions(List<Ast.Func> decls) {
    ImmutableList.Builder<Ast.Func> funcsToCheck = ImmutableList.builder();
    for (Ast.Func func : decls) {
      Ast.FuncDecl decl = func.getDecl();
      String name = decl.getName().getName();
      if (syntaxError(name.isEmpty())) {
        continue;
      }
      if (externs.containsKey(name) || funcIdx.containsKey(name)) {
        err.error(decl.getPosition(), "Function with this name is already defined.");
        continue;
      }
      funcIdx.put(name, funcs.size());
      funcs.add(
          Prog.Func.newBuilder()
              .setReturnType(getDeclReturnType(decl))
              .addAllParams(getDeclTypes(decl)));
      funcsToCheck.add(func);
    }
    return funcsToCheck.build();
  }

  private List<Prog.Type> getDeclTypes(Ast.FuncDecl decl) {
    return decl.getParamsList().stream()
        .map(
            p -> {
              Prog.Type t = resolveType(p.getType());
              if (!Types.isCopyable(t)) {
                err.error(p.getType().getPosition(), "Function parameter is not copyable");
              }
              return t;
            })
        .collect(toImmutableList());
  }

  private void createGlobalInit(List<Ast.VarDecl> globalsList) {
    Prog.Func.Builder func = Prog.Func.newBuilder();
    func.setReturnType(Types.VOID);
    Prog.Block.Builder body = Prog.Block.newBuilder();

    // Register globals one at a time after they are initialized, to at least avoid direct
    // references to uninitialized globals. Circular references may still be possible since
    // functions called during initialization can refer to globals yet uninitialized.
    for (int i = 0; i < globalsList.size(); i++) {
      Ast.VarDecl decl = globalsList.get(i);
      String varName = decl.getName().getName();
      if (syntaxError(varName.isEmpty())) {
        continue;
      }
      if (scope.collides(varName)) {
        err.error(decl.getPosition(), "Global variable with this name already exists");
        continue;
      }
      Prog.VarRef varRef = Prog.VarRef.newBuilder().setGlobalIndex(i).build();
      Prog.Expr.Builder initExpr = Prog.Expr.newBuilder();
      Prog.Expr refExpr = initVariable(varRef, initExpr, decl);
      body.addBody(Prog.Statement.newBuilder().setExpression(initExpr));
      programBuilder.addGlobals(refExpr.getType());
      scope.put(varName, refExpr);
    }
    func.setBody(body);
    funcs.add(func);
  }

  private Prog.Expr initVariable(Prog.VarRef ref, Prog.Expr.Builder builder, Ast.VarDecl decl) {
    Prog.Type varType = resolveType(decl.getType());
    Prog.Expr value = checkInitVal(decl.getInit(), varType);
    if (varType.hasArray() && value.hasArray()) {
      // For arrays we update the type with the inferred dimensions from initialization.
      varType = Types.inheritDims(varType, value.getType());
    }
    Prog.Expr refExpr =
        Prog.Expr.newBuilder().setReference(ref).setType(varType).setLvalue(true).build();
    builder
        .setType(varType)
        .setAssignment(Prog.Assignment.newBuilder().setValue(value).setReference(refExpr));
    return refExpr;
  }

  private void checkFunctions(List<Ast.Func> functions) {
    boolean hasMain = false;
    for (Ast.Func func : functions) {
      String funcName = func.getDecl().getName().getName();
      int idx = funcIdx.get(funcName);
      if (funcName.equals("main")
          && !func.getDecl().hasReturnType()
          && func.getDecl().getParamsCount() == 0) {
        hasMain = true;
        // Add a call to main from init.
        Prog.Func.Builder init = funcs.get(0);
        init.setBody(
            init.getBody()
                .toBuilder()
                .addBody(
                    Prog.Statement.newBuilder()
                        .setExpression(
                            Prog.Expr.newBuilder()
                                .setCall(Prog.FuncCall.newBuilder().setFunction(idx)))));
      }
      Prog.Func.Builder funcBuilder = funcs.get(idx);
      checkFunction(funcBuilder, func);
    }
    if (!hasMain) {
      err.error(Ast.Pos.getDefaultInstance(), "No func main() {} found.");
    }
  }

  private void checkFunction(Prog.Func.Builder funcBuilder, Ast.Func func) {
    Prog.Block.Builder body = Prog.Block.newBuilder();
    scope = scope.push(funcBuilder, body);
    List<Ast.FuncParam> params = func.getDecl().getParamsList();
    // Register function parameters
    for (int i = 0; i < params.size(); i++) {
      Ast.FuncParam p = params.get(i);
      if (syntaxError(p.getName().getName().isEmpty())) {
        continue;
      }
      Prog.Expr refExpr =
          Prog.Expr.newBuilder()
              .setReference(Prog.VarRef.newBuilder().setFunctionParam(i))
              .setType(funcBuilder.getParams(i))
              .setLvalue(true)
              .build();
      scope.put(p.getName().getName(), refExpr);
    }
    checkStatement(func.getBody());
    if ((body.getBodyCount() == 0 || !body.getBody(scope.scope.getBodyCount() - 1).hasReturnValue())
        && !funcBuilder.getReturnType().equals(Types.VOID)) {
      err.error(func.getPosition(), "Function does not return");
    }
    funcBuilder.setBody(body);
    scope = scope.pop();
  }

  private void checkStatement(Ast.Statement body) {
    switch (body.getStatementCase()) {
      case BLOCK:
        // For AST blocks we push a new *naming* scope but not a new basic block. Conditional and
        // loop checks are responsible for pushing new blocks when required.
        scope = scope.push();
        body.getBlock().getBodyList().forEach(this::checkStatement);
        scope = scope.pop();
        break;
      case CONDITIONAL:
        checkConditional(body.getConditional());
        break;
      case LOOP:
        checkLoop(body.getLoop());
        break;
      case DECL:
        checkDecl(body.getDecl());
        break;
      case EXPRESSION:
        checkExpr(body.getExpression());
        break;
      case RETURNVALUE:
        checkReturn(body.getReturnValue());
        break;
      case STATEMENT_NOT_SET:
        syntaxError();
    }
  }

  private void checkDecl(Ast.VarDecl decl) {
    String name = decl.getName().getName();
    if (syntaxError(name.isEmpty())) {
      return;
    }
    if (scope.collides(name)) {
      err.error(decl.getPosition(), "Variable with this name already defined in same scope.");
      return;
    }
    Prog.VarRef varRef =
        Prog.VarRef.newBuilder().setFunctionIndex(scope.function.getVariablesCount()).build();
    Prog.Expr.Builder initExpr = Prog.Expr.newBuilder();
    Prog.Expr refExpr = initVariable(varRef, initExpr, decl);
    scope.scope.addBody(Prog.Statement.newBuilder().setExpression(initExpr).build());
    scope.put(name, refExpr);
    scope.function.addVariables(refExpr.getType());
  }

  private void checkLoop(Ast.Loop loop) {
    // We need a new naming scope before the body since the initializer is part of that naming
    // scope.
    scope = scope.push();
    // Check init first to generate it before the loop statement.
    if (loop.hasInit()) {
      checkStatement(loop.getInit());
    }
    Prog.Loop.Builder loopBuilder = Prog.Loop.newBuilder();
    if (loop.hasCondition()) {
      Prog.Expr cond = expr(loop.getCondition());
      expectType(cond.getType(), Types.BOOLEAN, loop.getCondition().getPosition());
      loopBuilder.setCondition(cond);
    } else {
      loopBuilder.setCondition(
          Prog.Expr.newBuilder()
              .setType(Types.BOOLEAN)
              .setConstant(Prog.Constant.newBuilder().setBoolConst(true)));
    }

    Prog.Block.Builder bodyBuilder = Prog.Block.newBuilder();
    scope = scope.push(bodyBuilder);
    checkStatement(loop.getBody());
    if (loop.hasIncrement()) {
      checkStatement(loop.getIncrement());
    }
    loopBuilder.setBody(bodyBuilder);
    scope = scope.pop();

    scope.scope.addBody(Prog.Statement.newBuilder().setLoop(loopBuilder));
    scope = scope.pop();
  }

  private void checkConditional(Ast.Conditional conditional) {
    Prog.Expr cond = expr(conditional.getCondition());
    expectType(cond.getType(), Types.BOOLEAN, conditional.getPosition());
    Prog.Conditional.Builder condBuilder = Prog.Conditional.newBuilder().setCondition(cond);

    Prog.Block.Builder bodyBuilder = Prog.Block.newBuilder();
    scope = scope.push(bodyBuilder);
    checkStatement(conditional.getBody());
    condBuilder.setBody(bodyBuilder);
    scope = scope.pop();

    if (conditional.hasElseBody()) {
      Prog.Block.Builder elseBodyBuilder = Prog.Block.newBuilder();
      scope = scope.push(elseBodyBuilder);
      checkStatement(conditional.getElseBody());
      condBuilder.setElseBody(bodyBuilder);
      scope = scope.pop();
    }

    scope.scope.addBody(Prog.Statement.newBuilder().setConditional(condBuilder));
  }

  private void checkReturn(Ast.ReturnValue returnValue) {
    Prog.Returns.Builder returnBuilder = Prog.Returns.newBuilder();
    Prog.Type functionReturnType = scope.function.getReturnType();
    if (returnValue.hasValue()) {
      if (functionReturnType.equals(Types.VOID)) {
        err.error(returnValue.getValue().getPosition(), "Return with value in void function");
      } else {
        Prog.Expr value = expr(returnValue.getValue());
        expectType(value.getType(), functionReturnType, returnValue.getPosition());
        returnBuilder.setReturnValue(value);
      }
    } else if (!functionReturnType.equals(Types.VOID)) {
      err.error(returnValue.getPosition(), "Return missing value");
    }
    scope.scope.addBody(Prog.Statement.newBuilder().setReturnValue(returnBuilder));
  }

  private void checkExpr(Ast.Expr expression) {
    scope.scope.addBody(Prog.Statement.newBuilder().setExpression(expr(expression)));
  }

  private Prog.Expr expr(Ast.Expr expr) {
    switch (expr.getExprCase()) {
      case BINARY:
        return checkBinary(expr.getBinary());
      case VALUE:
        return checkValue(expr.getValue());
      case CALL:
        return checkCall(expr.getCall());
      case REFERENCE:
        return checkReference(expr.getReference());
      case CONDITIONAL:
        return checkTernary(expr.getConditional());
      case ASSIGNMENT:
        return checkAssignment(expr.getAssignment());
      case UNARY:
        return checkUnary(expr.getUnary());
      case SELECT:
        return checkSelect(expr.getSelect());
      case STRUCT:
      case DEFAULT_INIT:
      case EXPR_NOT_SET:
      case ARRAY:
        syntaxError();
        return ERROR_EXPR;
      default:
        throw new IllegalStateException("Unimplemented expression?");
    }
  }

  private static final ImmutableMap<Ast.BinaryOp, Prog.BinaryOp> OP_CONV =
      ImmutableMap.<Ast.BinaryOp, Prog.BinaryOp>builder()
          .put(Ast.BinaryOp.LESS_THAN, Prog.BinaryOp.LESS_THAN)
          .put(Ast.BinaryOp.GREATER_THAN, Prog.BinaryOp.GREATER_THAN)
          .put(Ast.BinaryOp.LESS_EQUALS, Prog.BinaryOp.LESS_EQUALS)
          .put(Ast.BinaryOp.GREATER_EQUALS, Prog.BinaryOp.GREATER_EQUALS)
          .put(Ast.BinaryOp.EQUALS, Prog.BinaryOp.EQUALS)
          .put(Ast.BinaryOp.NOT_EQUALS, Prog.BinaryOp.NOT_EQUALS)
          .put(Ast.BinaryOp.ARRAY_ACCESS, Prog.BinaryOp.ARRAY_ACCESS)
          .put(Ast.BinaryOp.ADD, Prog.BinaryOp.ADD)
          .put(Ast.BinaryOp.SUBTRACT, Prog.BinaryOp.SUBTRACT)
          .put(Ast.BinaryOp.MULTIPLY, Prog.BinaryOp.MULTIPLY)
          .put(Ast.BinaryOp.DIVIDE, Prog.BinaryOp.DIVIDE)
          .put(Ast.BinaryOp.MODULO, Prog.BinaryOp.MODULO)
          .put(Ast.BinaryOp.AND, Prog.BinaryOp.AND)
          .put(Ast.BinaryOp.OR, Prog.BinaryOp.OR)
          .build();

  private Prog.Expr checkBinary(Ast.BinaryExpr binary) {
    Prog.Expr left = expr(binary.getLeft());
    Prog.Expr right = expr(binary.getRight());
    Prog.Expr.Builder e = Prog.Expr.newBuilder();
    Prog.BinaryOp op = OP_CONV.get(binary.getOperator());
    e.setBinary(Prog.BinaryExpr.newBuilder().setLeft(left).setRight(right).setOperator(op));
    if (op == Prog.BinaryOp.ARRAY_ACCESS) {
      e.setLvalue(true);
    }
    e.setType(
        binaryType(
            left,
            binary.getLeft().getPosition(),
            right,
            binary.getRight().getPosition(),
            binary.getOperator()));
    return e.build();
  }

  private Prog.Type binaryType(
      Prog.Expr left, Ast.Pos leftPos, Prog.Expr right, Ast.Pos rightPos, Ast.BinaryOp operator) {
    boolean errors = left.getType().equals(Types.ERROR) || right.getType().equals(Types.ERROR);
    switch (operator) {
      case LESS_THAN:
      case GREATER_THAN:
      case LESS_EQUALS:
      case GREATER_EQUALS:
      case EQUALS:
      case NOT_EQUALS:
        if (errors) {
          return Types.ERROR;
        }
        if (Types.isComparable(left.getType(), right.getType())) {
          return Types.BOOLEAN;
        }
        err.error(leftPos, "Values are not comparable");
        return Types.ERROR;
      case ARRAY_ACCESS:
        Prog.Type ltype = left.getType();
        if (!ltype.equals(Types.ERROR) && !ltype.hasArray()) {
          err.error(leftPos, "Indexing on non-array value");
          return Types.ERROR;
        }
        if (!expectType(right.getType(), Types.INT, rightPos)) {
          return Types.ERROR;
        }
        if (!errors) {
          return Types.subarray(left.getType());
        }
        return Types.ERROR;
      case ADD:
      case SUBTRACT:
      case MULTIPLY:
      case DIVIDE:
      case MODULO:
        if (expectType(left.getType(), Types.INT, leftPos)
            && expectType(right.getType(), Types.INT, rightPos)
            && !errors) {
          return Types.INT;
        }
        return Types.ERROR;
      case AND:
      case OR:
        if (expectType(left.getType(), Types.BOOLEAN, leftPos)
            && expectType(right.getType(), Types.BOOLEAN, rightPos)
            && !errors) {
          return Types.BOOLEAN;
        } else {
          return Types.ERROR;
        }
      case UNRECOGNIZED:
      case BINARY_OP_UNSPECIFIED:
        syntaxError();
        return Types.ERROR;
      default:
        throw new IllegalStateException("Unhandled expression?");
    }
  }

  private Prog.Expr checkCall(Ast.FuncCall call) {
    List<Prog.Expr> paramVals =
        call.getParamsList().stream().map(this::expr).collect(toImmutableList());
    String name = call.getFunction().getName();
    if (syntaxError(name.isEmpty())) {
      return ERROR_EXPR;
    }
    if (externs.containsKey(name)) {
      return checkExtern(call, externs.get(name), paramVals);
    } else if (funcIdx.containsKey(name)) {
      return checkFunc(call, funcIdx.get(name), paramVals);
    } else {
      err.error(call.getFunction().getPosition(), "Function not found");
      return ERROR_EXPR;
    }
  }

  private Prog.Expr checkFunc(Ast.FuncCall call, int pos, List<Prog.Expr> paramVals) {
    Prog.Func.Builder called = funcs.get(pos);
    List<Prog.Type> callTypes =
        paramVals.stream().map(Prog.Expr::getType).collect(Collectors.toList());
    int functionParams = called.getParamsCount();
    int callParams = callTypes.size();
    if (callParams != functionParams) {
      err.error(
          call.getPosition(), "Got " + callParams + " parameters, expected " + functionParams);
    } else {
      for (int i = 0; i < paramVals.size(); i++) {
        if (!Types.isAssignable(called.getParams(i), callTypes.get(i))) {
          err.error(call.getParams(i).getPosition(), "Value not assignable to parameter");
        }
      }
    }
    return Prog.Expr.newBuilder()
        .setType(called.getReturnType())
        .setCall(Prog.FuncCall.newBuilder().setFunction(pos).addAllParams(paramVals))
        .build();
  }

  private Prog.Expr checkExtern(Ast.FuncCall call, FuncData funcData, List<Prog.Expr> paramVals) {
    List<Prog.Type> callTypes =
        paramVals.stream().map(Prog.Expr::getType).collect(Collectors.toList());
    int functionParams = funcData.argTypes.size();
    int callParams = callTypes.size();
    if (callParams != functionParams) {
      err.error(
          call.getPosition(), "Got " + callParams + " parameters, expected " + functionParams);
    } else {
      for (int i = 0; i < paramVals.size(); i++) {
        if (!Types.isAssignable(funcData.argTypes.get(i), callTypes.get(i))) {
          err.error(call.getParams(i).getPosition(), "Value not assignable to parameter");
        }
      }
    }
    return Prog.Expr.newBuilder()
        .setType(funcData.returnType)
        .setExtern(
            Prog.ExternCall.newBuilder()
                .setName(call.getFunction().getName())
                .addAllParams(paramVals))
        .build();
  }

  private Prog.Expr checkAssignment(Ast.Assignment astAssignment) {
    Prog.Expr reference = expr(astAssignment.getReference());
    Prog.Expr val = expr(astAssignment.getValue());

    if (!Types.isAssignable(reference.getType(), val.getType())) {
      err.error(astAssignment.getPosition(), "Left type is not assignable from right type");
      return ERROR_EXPR;
    }
    if (!reference.getLvalue()) {
      err.error(astAssignment.getPosition(), "Left type is not lvalue");
      return ERROR_EXPR;
    }

    Prog.Expr.Builder expr = Prog.Expr.newBuilder().setType(reference.getType());
    Prog.Assignment.Builder assignment =
        Prog.Assignment.newBuilder().setReference(reference).setValue(val);

    Ast.BinaryOp compoundOperator = astAssignment.getCompound();
    if (compoundOperator != Ast.BinaryOp.BINARY_OP_UNSPECIFIED) {
      assignment.setOperator(OP_CONV.get(compoundOperator));
      if (!binaryType(
              reference,
              astAssignment.getReference().getPosition(),
              val,
              astAssignment.getValue().getPosition(),
              compoundOperator)
          .equals(Types.ERROR)) {
        return ERROR_EXPR;
      }
    }
    expr.setAssignment(assignment);
    return expr.build();
  }

  private Prog.Expr checkInitVal(Ast.Expr value, Prog.Type type) {
    if (value.hasStruct()) {
      return structInit(value, type);
    }
    if (value.hasArray()) {
      return arrayInit(value, type);
    }
    if (value.hasDefaultInit()) {
      return defaultValue(value, type);
    }
    if (!Types.isCopyable(type)) {
      err.error(value.getPosition(), "Variable type is not assignable");
      return ERROR_EXPR;
    }
    Prog.Expr expr = expr(value);
    if (!Types.isAssignable(type, expr.getType())) {
      err.error(
          value.getPosition(),
          Types.asString(expr.getType(), this)
              + " not assignable to "
              + Types.asString(type, this));
      return ERROR_EXPR;
    }
    return expr;
  }

  private Prog.Expr arrayInit(Ast.Expr value, Prog.Type type) {
    if (!type.hasArray()) {
      err.error(value.getPosition(), "Initializing non-array with array value");
      return ERROR_EXPR;
    }
    List<Integer> inferredDimensions = new ArrayList<>(type.getArray().getDimensionsList());
    resolveInferred(value, inferredDimensions);
    Prog.Type.Builder inferredType = type.toBuilder();
    inferredType.setArray(
        inferredType.getArray().toBuilder().clearDimensions().addAllDimensions(inferredDimensions));
    type = inferredType.build();
    long totSize = Types.arraySize(type);
    if (totSize == 0) {
      err.error(value.getPosition(), "Could not infer all array dimensions");
      return ERROR_EXPR;
    }
    if (totSize == Integer.MAX_VALUE) {
      err.error(value.getPosition(), "Array size too large");
      return ERROR_EXPR;
    }
    Prog.ArrayInit.Init.Builder arrayInit =
        arrayInitValues(value, type.getArray().getArrayOf(), inferredDimensions);
    return Prog.Expr.newBuilder()
        .setType(inferredType)
        .setArray(Prog.ArrayInit.newBuilder().setTotalSize((int) totSize).setInit(arrayInit))
        .build();
  }

  private Prog.ArrayInit.Init.Builder arrayInitValues(
      Ast.Expr value, Prog.Type base, List<Integer> dimensions) {
    Prog.ArrayInit.Init.Builder initBuilder =
        Prog.ArrayInit.Init.newBuilder().setDimension(dimensions.get(0));
    if (dimensions.size() == 1) {
      // Scalar array
      Prog.ArrayInit.ScalarArray.Builder scalars = Prog.ArrayInit.ScalarArray.newBuilder();
      if (value.hasArray()) {
        Ast.ArrayLit array = value.getArray();
        List<Ast.Expr> valuesList = array.getValuesList();
        valuesList.forEach(
            v -> {
              Prog.Expr expr = checkInitVal(v, base);
              expectType(expr.getType(), base, v.getPosition());
              scalars.addValues(expr);
            });
        if (array.getShouldFill()) {
          if (array.hasFill()) {
            Prog.Expr expr = checkInitVal(array.getFill(), base);
            expectType(expr.getType(), base, array.getFill().getPosition());
            scalars.setFill(expr);
          }
          if (valuesList.size() > dimensions.get(0)) {
            err.error(value.getPosition(), "Expected at most " + dimensions.get(0) + " values");
          }
        } else if (valuesList.size() != dimensions.get(0)) {
          err.error(value.getPosition(), "Expected " + dimensions.get(0) + " values");
        }
      } else if (value.hasDefaultInit()) {
        scalars.setFill(checkInitVal(value, base));
      } else {
        err.error(value.getPosition(), "Expected array");
      }
      initBuilder.setScalars(scalars);
    } else {
      // Array-of-arrays
      Prog.ArrayInit.SubArrays.Builder subarray = Prog.ArrayInit.SubArrays.newBuilder();
      List<Integer> subdims = dimensions.subList(1, dimensions.size());
      if (value.hasArray()) {
        Ast.ArrayLit array = value.getArray();
        List<Ast.Expr> valuesList = array.getValuesList();
        valuesList.forEach(v -> subarray.addValues(arrayInitValues(v, base, subdims)));
        if (array.getShouldFill()) {
          if (array.hasFill()) {
            subarray.setFill(arrayInitValues(array.getFill(), base, subdims));
          }
          if (valuesList.size() > dimensions.get(0)) {
            err.error(value.getPosition(), "Expected at most " + dimensions.get(0) + " values");
          }
        } else if (valuesList.size() != dimensions.get(0)) {
          err.error(value.getPosition(), "Expected " + dimensions.get(0) + " values");
        }
      } else if (value.hasDefaultInit()) {
        subarray.setFill(arrayInitValues(value, base, subdims));
      } else {
        err.error(value.getPosition(), "Expected subarray");
      }
      initBuilder.setSubarray(subarray);
    }
    return initBuilder;
  }

  private Prog.Expr defaultValue(Ast.Expr value, Prog.Type type) {
    if (type.hasArray()) {
      return arrayInit(value, type);
    }
    if (type.getTypeCase() == Prog.Type.TypeCase.STRUCT) {
      return structInit(value, type);
    }
    if (type.equals(Types.INT)) {
      return Prog.Expr.newBuilder()
          .setType(Types.INT)
          .setConstant(Prog.Constant.newBuilder().setIntConst(0))
          .build();
    } else if (type.equals(Types.BOOLEAN)) {
      return Prog.Expr.newBuilder()
          .setType(Types.BOOLEAN)
          .setConstant(Prog.Constant.newBuilder().setBoolConst(false))
          .build();
    } else if (type.equals(Types.CHAR)) {
      return Prog.Expr.newBuilder()
          .setType(Types.CHAR)
          .setConstant(Prog.Constant.newBuilder().setCharConst(0))
          .build();
    } else if (type.equals(Types.STRING)) {
      return Prog.Expr.newBuilder()
          .setType(Types.STRING)
          .setConstant(Prog.Constant.newBuilder().setStringConst(""))
          .build();
    } else if (type.equals(Types.ERROR)) {
      return ERROR_EXPR;
    }
    throw new IllegalStateException("Unexpected type");
  }

  private void resolveInferred(Ast.Expr value, List<Integer> inferredDimensions) {
    if (inferredDimensions.isEmpty()) {
      return;
    }
    if (value.hasArray()) {
      if (!value.getArray().getShouldFill()) {
        int len = value.getArray().getValuesCount();
        if (inferredDimensions.get(0) == 0) {
          inferredDimensions.set(0, len);
        }
      }
      value
          .getArray()
          .getValuesList()
          .forEach(
              v -> {
                resolveInferred(v, inferredDimensions.subList(1, inferredDimensions.size()));
              });
    }
  }

  private Prog.Expr structInit(Ast.Expr value, Prog.Type type) {
    if (type.equals(Types.ERROR)) {
      return ERROR_EXPR;
    }
    if (type.getTypeCase() != Prog.Type.TypeCase.STRUCT) {
      err.error(value.getPosition(), "Struct initializer used for non-struct type");
      return ERROR_EXPR;
    }
    if (value.hasDefaultInit()) {
      Prog.StructInit.Builder struct = Prog.StructInit.newBuilder();
      List<Prog.Type> fieldTypes = programBuilder.getStructs(type.getStruct()).getFieldsList();
      for (int i = 0; i < fieldTypes.size(); i++) {
        struct.putValues(i, defaultValue(value, fieldTypes.get(i)));
      }
      return Prog.Expr.newBuilder().setType(type).setStruct(struct).build();
    }
    if (value.hasStruct()) {
      StructData structData = structsIdx.get(type.getStruct());
      Prog.StructInit.Builder struct = Prog.StructInit.newBuilder();
      value
          .getStruct()
          .getValuesList()
          .forEach(
              v -> {
                String name = v.getField().getName();
                if (syntaxError(name.isEmpty())) {
                  return;
                }
                if (!structData.fields.containsKey(name)) {
                  err.error(
                      v.getField().getPosition(),
                      "No field " + name + " in struct " + structData.name);
                } else {
                  Integer fieldIdx = structData.fields.get(name);
                  Prog.Type fieldType =
                      programBuilder.getStructs(structData.index).getFields(fieldIdx);
                  Prog.Expr fieldValue = checkInitVal(v.getValue(), fieldType);
                  expectType(fieldValue.getType(), fieldType, v.getValue().getPosition());
                  struct.putValues(fieldIdx, fieldValue);
                }
              });
      return Prog.Expr.newBuilder().setType(type).setStruct(struct).build();
    }
    return expr(value);
  }

  private Prog.Expr checkReference(Ast.Identifier reference) {
    Optional<Prog.Expr> resolve = scope.resolve(reference.getName());
    if (!resolve.isPresent()) {
      err.error(reference.getPosition(), "No such variable");
      return ERROR_EXPR;
    }
    return resolve.get();
  }

  private Prog.Expr checkTernary(Ast.Ternary conditional) {
    Prog.Expr cond = expr(conditional.getCond());
    expectType(cond.getType(), Types.BOOLEAN, conditional.getCond().getPosition());
    Prog.Expr left = expr(conditional.getLeft());
    Prog.Expr right = expr(conditional.getRight());
    Optional<Prog.Type> common = Types.unify(left.getType(), right.getType());
    if (!common.isPresent()) {
      err.error(conditional.getPosition(), "Ternary subexpressions have incompatible type");
      return ERROR_EXPR;
    }
    return Prog.Expr.newBuilder()
        .setType(common.get())
        .setConditional(Prog.Ternary.newBuilder().setCond(cond).setLeft(left).setRight(right))
        .build();
  }

  private Prog.Expr checkValue(Ast.Value value) {
    Prog.Constant.Builder constant = Prog.Constant.newBuilder();
    Prog.Expr.Builder b = Prog.Expr.newBuilder();
    switch (value.getLiteralCase()) {
      case BOOL_LITERAL:
        constant.setBoolConst(value.getBoolLiteral());
        b.setType(Types.BOOLEAN);
        break;
      case INT_LITERAL:
        constant.setIntConst(value.getIntLiteral());
        b.setType(Types.INT);
        break;
      case STRING_LITERAL:
        constant.setStringConst(value.getStringLiteral());
        b.setType(Types.STRING);
        break;
      case CHAR_LITERAL:
        constant.setCharConst(value.getCharLiteral());
        b.setType(Types.CHAR);
        break;
      case LITERAL_NOT_SET:
        syntaxError();
        return ERROR_EXPR;
      default:
        throw new IllegalStateException();
    }
    return b.setConstant(constant).build();
  }

  private static final ImmutableMap<Ast.UnaryOp, Prog.UnaryOp> UNARY_OP_CONV =
      ImmutableMap.<Ast.UnaryOp, Prog.UnaryOp>builder()
          .put(Ast.UnaryOp.NEGATE, Prog.UnaryOp.NEGATE)
          .put(Ast.UnaryOp.NOT, Prog.UnaryOp.NOT)
          .put(Ast.UnaryOp.POSTFIX_DECREMENT, Prog.UnaryOp.POSTFIX_DECREMENT)
          .put(Ast.UnaryOp.POSTFIX_INCREMENT, Prog.UnaryOp.POSTFIX_INCREMENT)
          .put(Ast.UnaryOp.PREFIX_DECREMENT, Prog.UnaryOp.PREFIX_DECREMENT)
          .put(Ast.UnaryOp.PREFIX_INCREMENT, Prog.UnaryOp.PREFIX_INCREMENT)
          .build();

  private Prog.Expr checkUnary(Ast.UnaryExpr unary) {
    Prog.Expr val = expr(unary.getExpr());
    Prog.UnaryExpr.Builder unaryBuilder =
        Prog.UnaryExpr.newBuilder()
            .setExpr(val)
            .setOperator(UNARY_OP_CONV.get(unary.getOperator()));
    Prog.Expr.Builder e = Prog.Expr.newBuilder().setType(val.getType()).setUnary(unaryBuilder);
    switch (unary.getOperator()) {
      case NEGATE:
        if (!expectType(val.getType(), Types.INT, unary.getExpr().getPosition())) {
          return ERROR_EXPR;
        }
        break;
      case NOT:
        if (!expectType(val.getType(), Types.BOOLEAN, unary.getExpr().getPosition())) {
          return ERROR_EXPR;
        }
        break;
      case PREFIX_INCREMENT:
      case PREFIX_DECREMENT:
      case POSTFIX_INCREMENT:
      case POSTFIX_DECREMENT:
        if (!val.getLvalue()) {
          err.error(unary.getExpr().getPosition(), "Expected lvalue");
          return ERROR_EXPR;
        }
        if (!expectType(val.getType(), Types.INT, unary.getExpr().getPosition())) {
          return ERROR_EXPR;
        }
        break;
      case UNARY_OP_UNSPECIFIED:
      case UNRECOGNIZED:
        syntaxError();
        return ERROR_EXPR;
      default:
        throw new IllegalStateException();
    }
    return e.build();
  }

  private Prog.Expr checkSelect(Ast.Select select) {
    Prog.Expr value = expr(select.getCalledOn());
    // Just propagate errors up; doesn't matter that it's not the semantically correct expression
    // during type check.
    if (value.getType().equals(Types.ERROR)) {
      return value;
    }
    Prog.Expr.Builder expr = Prog.Expr.newBuilder().setLvalue(true);
    Prog.Select.Builder selectBuilder = Prog.Select.newBuilder().setValue(value);
    String name = select.getField().getName();
    if (syntaxError(name.isEmpty())) {
      return ERROR_EXPR;
    } else {
      Optional<StructData> maybeStruct = resolveStruct(value.getType());
      if (maybeStruct.isPresent()) {
        StructData struct = maybeStruct.get();
        if (!struct.fields.containsKey(name)) {
          err.error(
              select.getField().getPosition(), "No field " + name + " in struct " + struct.name);
          expr.setType(Types.ERROR);
        } else {
          Integer fieldIdx = struct.fields.get(name);
          selectBuilder.setField(fieldIdx);
          expr.setType(programBuilder.getStructs(struct.index).getFields(fieldIdx));
        }
      } else {
        if (!value.getType().equals(Types.ERROR)) {
          err.error(select.getField().getPosition(), "Cannot get field on non-struct");
        }
        return ERROR_EXPR;
      }
    }
    return expr.setSelect(selectBuilder).build();
  }

  private Optional<StructData> resolveStruct(Prog.Type type) {
    if (type.getTypeCase() != Prog.Type.TypeCase.STRUCT) {
      return Optional.empty();
    }
    return Optional.of(structsIdx.get(type.getStruct()));
  }

  private Prog.Type resolveType(Ast.Type type) {
    if (syntaxError(type.getName().isEmpty())) {
      return Types.ERROR;
    }
    Prog.Type resolved = Types.resolve(type, this);
    if (resolved.equals(Types.ERROR)) {
      err.error(type.getPosition(), "Unknown type");
    }
    return resolved;
  }

  private boolean expectType(Prog.Type has, Prog.Type expected, Ast.Pos pos) {
    // Error type checks with anything to avoid error messages where generated.
    if (has.equals(Types.ERROR) || expected.equals(Types.ERROR)) {
      return true;
    }
    if (!Types.isAssignable(expected, has)) {
      err.error(
          pos,
          "Expected type "
              + Types.asString(expected, this)
              + " but was "
              + Types.asString(has, this));
      return false;
    }
    return true;
  }

  private boolean syntaxError(boolean isError) {
    if (isError) {
      syntaxError();
    }
    return isError;
  }

  private void syntaxError() {
    if (!permissive) {
      throw new IllegalArgumentException();
    }
  }

  public static Prog.Program typeCheck(Ast.Program program, Errors err) {
    return new TypeChecker(!program.getValid(), err).checkProgram(program);
  }

  private static class Scope {
    private final Scope parent;
    private final HashMap<String, Prog.Expr> vars = new HashMap<>();
    public Prog.Func.Builder function;
    public Prog.Block.Builder scope;

    private Scope(Scope parent, Prog.Func.Builder function, Prog.Block.Builder scope) {
      this.parent = parent;
      this.function = function;
      this.scope = scope;
    }

    public Optional<Prog.Expr> resolve(String name) {
      if (vars.containsKey(name)) {
        return Optional.of(vars.get(name));
      }
      if (parent != null) {
        return parent.resolve(name);
      }
      return Optional.empty();
    }

    public boolean collides(String name) {
      return vars.containsKey(name);
    }

    public void put(String name, Prog.Expr ref) {
      checkArgument(!vars.containsKey(name));
      vars.put(name, ref);
    }

    @CheckReturnValue
    public Scope push() {
      return new Scope(this, function, scope);
    }

    @CheckReturnValue
    public Scope push(Prog.Block.Builder scope) {
      return new Scope(this, function, scope);
    }

    @CheckReturnValue
    public Scope push(Prog.Func.Builder function, Prog.Block.Builder scope) {
      return new Scope(this, function, scope);
    }

    @CheckReturnValue
    public Scope pop() {
      return this.parent;
    }

    @CheckReturnValue
    static Scope root() {
      return new Scope(null, null, null);
    }
  }

  private static class FuncData {
    Prog.Type returnType;
    List<Prog.Type> argTypes;

    public FuncData(Prog.Type returnType, List<Prog.Type> argTypes) {
      this.returnType = returnType;
      this.argTypes = argTypes;
    }
  }

  static class StructData {
    HashMap<String, Integer> fields = new HashMap<>(); // The indices of the field in programBuilder
    int index; // The index of this struct in the programBuilder
    String name;
    // Initialized to ERROR since there may be references to the type in cyclic struct dependencies
    // before this is set
    Prog.Type type = Types.ERROR;
  }
}

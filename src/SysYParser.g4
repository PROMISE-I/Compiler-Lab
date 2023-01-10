parser grammar SysYParser;

options {
    tokenVocab = SysYLexer;
}

program
   : compUnit
   ;

compUnit
   : (funcDef | decl)+ EOF
   ;
// 下面是其他的语法单元定义
decl : constDecl
     | varDecl
     ;

constDecl : CONST bType constDef ( COMMA constDef )* SEMICOLON ;

bType : INT ;

constDef : IDENT ( L_BRACKT constExp R_BRACKT )* ASSIGN constInitVal ;

constInitVal : constExp #ConstExpConstInitVal
             | L_BRACE ( constInitVal ( COMMA constInitVal )* )? R_BRACE    #ArrayConstInitVal
             ;

varDecl : bType varDef ( COMMA varDef )* SEMICOLON ;

varDef : IDENT ( L_BRACKT constExp R_BRACKT )*
       | IDENT ( L_BRACKT constExp R_BRACKT )* ASSIGN initVal
       ;

initVal : exp   #ExpInitVal
        | L_BRACE ( initVal ( COMMA initVal )* )? R_BRACE   #ArrayInitVal
        ;

funcDef : funcType IDENT L_PAREN (funcFParams)? R_PAREN block ;

funcType : VOID
         | INT
         ;

funcFParams : funcFParam ( COMMA funcFParam )* ;

funcFParam : bType IDENT (L_BRACKT R_BRACKT ( L_BRACKT exp R_BRACKT )*)? ;

block : L_BRACE ( blockItem )* R_BRACE ;

blockItem : decl
          | stmt
          ;

stmt : lVal ASSIGN exp SEMICOLON    #AssignStmt
     | (exp)? SEMICOLON     #ExpStmt
     | block    #BlockStmt
     | IF L_PAREN cond R_PAREN if_stmt = stmt ( ELSE else_stmt = stmt )?  #IfStmt
     | WHILE L_PAREN cond R_PAREN stmt  #WhileStmt
     | BREAK SEMICOLON  #BreakStmt
     | CONTINUE SEMICOLON   #ContinueStmt
     | RETURN (exp)? SEMICOLON  #ReturnStmt
     ;

exp
   : L_PAREN exp R_PAREN    #ParenExp
   | lVal   #lValExp
   | number #NumberExp
   | IDENT L_PAREN funcRParams? R_PAREN #CallExp
   | unaryOp exp    #UnaryExp
   | lhs = exp op = (MUL | DIV | MOD) rhs = exp  #MulDivModExp
   | lhs = exp op = (PLUS | MINUS) rhs = exp #PlusMinusExp
   ;

cond
   : exp    #ExpCond
   | l_cond = cond op = (LT | GT | LE | GE) r_cond = cond      #GLCond
   | l_cond = cond op = (EQ | NEQ) r_cond = cond   #EQCond
   | l_cond = cond AND r_cond = cond  #AndCond
   | l_cond = cond OR r_cond = cond   #OrCond
   ;

lVal
   : IDENT (L_BRACKT exp R_BRACKT)*
   ;

number
   : INTEGR_CONST
   ;

unaryOp
   : PLUS
   | MINUS
   | NOT
   ;

funcRParams
   : param (COMMA param)*
   ;

param
   : exp
   ;

constExp
   : exp
   ;

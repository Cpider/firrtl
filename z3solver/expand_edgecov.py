import re
import argparse
import fnmatch
import os
from gen_cfg import *

parser = argparse.ArgumentParser(
                    prog='expand_edgecov.py',
                    description='Rewrite the simulator c++ code, encode the data flow to control flow.',
                    epilog='Writed by Yinshuai')

parser.add_argument('-v', "--vdir", metavar='N', type=str, default='',
                    help='The directory of verilog files.')

parser.add_argument('-s', "--sdir", metavar='N', type=str, default='',
                    help='The directory of verilated cpp files.')

parser.add_argument('-m', "--module", metavar='N', type=str, default='',
                    help='Module need to convert.')

args = parser.parse_args()

cpp_assign = re.compile(r"([^\s]+) = (.*);")
stmt_terminal = ['{', '}', '//', '#include']

op_match = re.compile(r"^[^\w,\(\)]+$")
opkeyword = set()
oplist = {}

class SIMRefactor:
    def __init__(self, vdir = '', sdir = ''):
        self.vdir = vdir
        self.sdir = sdir
        self.mod_class = {}
        self.expand_sig = []
        self.refact_file = []
        
    def refactor_module(self, module):
        for file in os.listdir(self.vdir):
            if fnmatch.fnmatch(file, f'{module}.v'):
                v_path = os.path.join(self.vdir, file)
                # print(v_path)
                if os.path.isfile(v_path):
                    module_cfg = HDL_CFG(v_path)
                    module_cfg.parse_verilog()
                    # print(module_cfg.wires)
                    self.expand_sig = module_cfg.df_convert_cf()
                    self.mod_class[module] = module_cfg
                else:
                    print(f"[Error] {module} verilog file is not exisit!")
        print(self.expand_sig)
        print(len(self.expand_sig), len(set(self.expand_sig)))
        input()
        if len(self.expand_sig) != 0:
            for file in os.listdir(self.sdir):
                if fnmatch.fnmatch(file, f'VTestHarness_{module}__DepSet*.cpp'):
                    s_path = os.path.join(self.sdir, file)
                    # print(s_path)
                    if os.path.isfile(s_path):
                        self.refact_file.append(s_path)
        if len(self.refact_file) != 0:
            # for file in self.refact_file:
            #     rewrite_file = 'rewrite_' + file
            #     self.refactor_cpp(file, rewrite_file, self.expand_sig)
            file = self.refact_file[-1]
            print(file)
            rewrite_file = os.path.join(os.path.dirname(file), 'rewrite_' + os.path.basename(file))
            self.refactor_cpp(file, rewrite_file, self.expand_sig)
            print(f"opkeyword: {opkeyword}")
            # Check oplist
            for num, opl in oplist.items():
                print(f"op num is: {num}")
                for op in opl:
                    print(f"{op}")

    def ops_collect(self, ops):
        if len(ops) == 0:
            return
        global oplist
        temp_ops = oplist.get(len(ops))
        if temp_ops:
            eq = 0
            for i in temp_ops:
                for j in range(len(ops)):
                    if i[j] == ops[j]:
                        eq = 1
                    else:
                        eq = 0
                        break
                if eq == 1:
                    break
            if eq == 0:
                oplist[len(ops)].append(ops)

        else:
            oplist[len(ops)] = [ops]

    def refactor_cpp(self, orig_file, rewrite_file, expand_sig):
        with open(orig_file, 'r')  as of:
            o_codes = of.readlines()
        with open(rewrite_file, 'w') as ref:
            statement = ''
            for single_line in o_codes:
                if any(char in single_line for char in stmt_terminal):
                    ref.write(single_line)
                    continue
                if single_line == '\n':
                    ref.write(single_line)
                    continue
                if ';' not in single_line:
                    statement += ' ' + single_line.strip()
                    continue
                
                if statement == '':
                    statement = single_line

                else:
                    statement += ' ' + single_line.strip()
                # print(f"stmt is: {statement}")
                assign_stmt = cpp_assign.search(statement)
                if assign_stmt:
                    # print(f"stmt is: {statement}")
                    sink = assign_stmt.group(1)
                    src_expr = assign_stmt.group(2)
                    if sink.split('__')[-1] in expand_sig:
                        print(f"stmt is: {statement}")
                        print(f"sink is: {sink}, srcs expr is: {src_expr}")
                        ops, opds = self.parse_expression(src_expr)
                        print(f"ops is: {ops}")
                        self.ops_collect(ops)
                        converted_text = '\n'.join(self.convert(ops, opds, sink))
                        print(converted_text)
                        ref.write(converted_text + '\n')
                        statement = ''
                    else:
                        ref.write(' ' * 4 + statement.strip() + '\n')
                        statement = ''

                else:
                    ref.write(' ' * 4 + statement + '\n')
                    statement = ''

    def parse_expression(self, expression):
        parse_operators = []
        parse_operands = []
        operators = []
        operands = []

        i = 0
        word_list = list()
        word = ''
        unclose = [0]
        bracket_index = []
        op_index = []
        global opkeyword
        prev_word = ''

        while i < len(expression):
            if expression[i] == '(':
                # print(f"( word: {word}")
                prev_word = ''
                if len(word_list) != 0:
                    prev_word = word_list.pop()

                if op_match.match(prev_word):
                    # print(f"op match word: {prev_word}, word_list: {word_list}, parse_operand: {parse_operands}")
                    opkeyword.add(prev_word)

                    # if len(parse_operands) == 0:
                    temp = ''
                    while '(' != word_list[-1][-1]:
                        temp = word_list.pop() + temp

                    parse_operands.append({'lhs': temp})
                    # print(f"op {word}, temp: {temp}, oprands: {parse_operands}")
                    # bracket_index.append(len(word_list))

                    word_list.append(temp)
                    op_index.append(len(word_list) + 1)

                    parse_operators.append(prev_word)

                if prev_word != '':
                    word_list.append(prev_word)
                word_list.append(word + '(')
                bracket_index.append(len(word_list))

                # print(f"(fini word_list: {word_list}, word: {word}, bracket_index: {bracket_index}, parse_operand: {parse_operands}")
                word = ''

            elif expression[i] == ')':
                unclose[-1] -= 1
                if word != '':
                    word_list.append(word)  
                # print(f") begin: word: {word}, word_list: {word_list}, bracket_index: {bracket_index}")

                if len(op_index) != 0 and len(bracket_index) != 0:
                    if bracket_index[-1] > op_index[-1]:
                        temp = ')'
                        while '(' != word_list[-1][-1]:
                            temp = word_list.pop() + temp
                        temp = word_list.pop() + temp
                        bracket_index.pop()
                        word_list.append(temp)
                        # print(f"): temp {temp}, bracket_index: {bracket_index}, op_index: {op_index}, word_list: {word_list}, word: {word}")
                    else:
                        temp = ''
                        n = len(word_list)
                        for j in range(op_index[-1], n):
                            temp = word_list.pop() + temp
                        parse_operands[-1]['rhs'] = temp
                        word_list.append(temp)
                        operands.append(parse_operands.pop())
                        operators.append(parse_operators.pop())  
                        # print(f"[finish] pop op: {operands[-1]}, {operators[-1]}")
                        temp = ''

                        n = len(word_list)
                        temp += ')'
                        for j in range(n, bracket_index[-1] - 1, -1):
                            if j == op_index[-1]:
                                temp = ' ' + word_list.pop() + ' ' + temp
                            else:
                                temp = word_list.pop() + temp
                            # print(f"word_list pop: {temp}")
                        if len(parse_operands) != 0:
                            parse_operands[-1]['rhs'] = temp

                        word_list.append(temp)
                        bracket_index.pop()
                        op_index.pop()
                        # print(f"): temp {temp}, bracket_index: {bracket_index}, op_index: {op_index}, word_list: {word_list}, word: {word}")
                elif len(op_index) == 0 and len(bracket_index) != 0:
                    temp = ')'
                    while '(' != word_list[-1][-1]:
                        temp = word_list.pop() + temp
                    temp = word_list.pop() + temp
                    bracket_index.pop()
                    word_list.append(temp)
                    # print(f"): temp {temp}, bracket_index: {bracket_index}, op_index: {op_index}, word_list: {word_list}, word: {word}")

                word = ''
                if len(bracket_index) == 0:
                    break
                
            elif expression[i] == ' ':
                if word != '':
                    word_list.append(word)
                    word = ''   
            else:
                word += expression[i]
            i += 1

        if len(operands) == 0 and len(operators) == 0:
            # print(f"op0: {word_list} word: {word}")
            if len(word_list) != 0:
                operands.append({'lhs' : word_list.pop()})
            else:
                operands.append({'lhs' : word})
        elif len(operands) == 0 and len(operators) != 0 or len(operands) != 0 and len(operators) == 0:
            print(f"[ERROR] the operands {operands} and operators {operators} doesn't match!")
        
        # print(operators)
        # print(operands)
        # print(word_list)
        return operators, operands


    def convert(self, ops, oprands, sink = ''):
        i = 0
        leaf_node = [0]
        converted = ['1']

        def insert_node(leaf_node, converted, node, op = '', level = 1):
            new_leaf = []
            index = 0
            insert_line = 0
            for leaf_num in leaf_node:
                assign_value = converted.pop(leaf_num + insert_line)
                index = leaf_num + insert_line
                insert_line -= 1
                # print(f"index is: {index}, converted is: {converted}")
                # print(f"new_leaf is: {new_leaf}")
                converted.insert(index, " " * 4 * level + f"if ({node}) {{")
                index += 1
                insert_line += 1
                # print(f"assign_value is: {assign_value}")
                if op == '&':
                    # print(f"& {assign_value}")
                    converted.insert(index, assign_value)
                elif op == '|':
                    # print(f"| {assign_value}")
                    converted.insert(index, '1')
                elif op == '^':
                    # print(f"^ {assign_value}")
                    converted.insert(index, str(1 ^ int(assign_value)))
                else:
                    # print(f"Nop {op} {assign_value}")
                    converted.insert(index, assign_value)
                    # print(f"[OPWARNING] {op} is not legal")
                new_leaf.append(index)

                index += 1
                insert_line += 1
                converted.insert(index, " " * 4 * level + "}")
                index += 1
                insert_line += 1
                converted.insert(index, " " * 4 * level + "else {")
                index += 1
                insert_line += 1
                if op == '&':
                    # print(f"& {assign_value}")
                    converted.insert(index, '0')
                elif op == '|':
                    # print(f"| {assign_value}")
                    converted.insert(index, assign_value)
                elif op == '^':
                    # print(f"^ {assign_value}")
                    converted.insert(index, str(0 ^ int(assign_value)))
                else:
                    # print(f"nop {assign_value}")
                    converted.insert(index, str(1 - int(assign_value)))
                    # print(f"[OPWARNING] {op} is not legal")
                new_leaf.append(index)
                

                index += 1
                insert_line += 1
                converted.insert(index, " " * 4 * level + "}")
                index += 1
                insert_line += 1
                # print(f"index is: {index}, converted is: {converted}")
            
            leaf_node.clear()
            leaf_node.extend(new_leaf)

            # print(converted)
            # print(f"leaf_node is: {leaf_node}")

        level = 1

        # Check if ops have the &, |, ^ operators, and check if its operands are 1 width signals.
        if len(ops) == 0:
            print(f"No ops")
            insert_node(leaf_node, converted, oprands[0]['lhs'], level = level)
            print(f"add node: {oprands[0]['lhs']}")
            level += 1

        elif '&' not in ops and '|' not in ops and '^' not in ops:
            node = oprands[-1]['lhs'] + ' ' + ops[-1] + ' ' + oprands[-1]['rhs']
            insert_node(leaf_node, converted, node, ops[-1], level = level)
            level += 1

        else:
            # TODO
            add_node_op = []
            op_w2o = ['>=', '<', '>', '==', '!=', '<=']
            logic_op = ['^', '&', '|']
            for op_index in range(len(ops)):
                if ops[op_index] in op_w2o:
                    if len(add_node_op) != 0:
                        temp = oprands[add_node_op[-1]]['lhs'] + ' ' + ops[add_node_op[-1]] + ' ' + oprands[add_node_op[-1]]['rhs']
                        if temp in oprands[op_index]['lhs'] or temp in oprands[op_index]['rhs']:
                            add_node_op.clear()
                elif ops[op_index] in logic_op:
                    add_node_op.append(op_index)
            if len(add_node_op) == 0:
                node = oprands[-1]['lhs'] + ' ' + ops[-1] + ' ' + oprands[-1]['rhs']
                insert_node(leaf_node, converted, node, ops[-1], level = level)
                level += 1
            else:
                print(f"op is: {ops}, add_node_ops is: {add_node_op}")
                insert_node(leaf_node, converted, oprands[add_node_op[0]]['lhs'], level = level)
                print(f"add node: {oprands[add_node_op[0]]['lhs']}")
                level += 1
                node = oprands[add_node_op[0]]['lhs']
                for op_index in add_node_op:
                    if node in oprands[op_index]['lhs']:
                        insert_node(leaf_node, converted, oprands[op_index]['rhs'], ops[op_index], level)
                        print(f"add node: {oprands[op_index]['rhs']}, op is: {ops[op_index]}")
                    else:
                        insert_node(leaf_node, converted, oprands[op_index]['lhs'], ops[op_index], level)
                        print(f"add node: {oprands[op_index]['lhs']}, op is: {ops[op_index]}")
                    level += 1
                    node = oprands[op_index]['lhs'] + ' ' + ops[op_index] + ' ' + oprands[op_index]['rhs']
        
        if sink != '':
            for i in leaf_node:
                converted[i] = " " * 4 * (level - 1) + f"    {sink} = {converted[i]};"
        # print('\n'.join(converted))
        return converted


test_file = 'VTestHarness_LSU__DepSet_h3bd28679__0.cpp'
write_file = 'rewrite.cpp'
def test():
    simrefact = SIMRefactor(args.vdir, args.sdir)
    simrefact.refactor_module(args.module)
    # simrefact.refactor_cpp(test_file, write_file)

test()


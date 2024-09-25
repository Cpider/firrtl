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
        # print(self.expand_sig)
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
                    statement += single_line.strip()
                    continue
                
                if statement == '':
                    statement = single_line

                else:
                    statement += ' ' + single_line.strip()
                # print(f"stmt is: {statement}")
                assign_stmt = cpp_assign.search(statement)
                print(f"statement is: {statement}")
                if assign_stmt:
                    # print(f"stmt is: {statement}")
                    sink = assign_stmt.group(1)
                    src_expr = assign_stmt.group(2)
                    print(f"sink is: {sink}, srcs expr is: {src_expr}")
                    if sink.split('__')[-1] in expand_sig:
                        ops, opds = self.parse_expression(src_expr)
                        converted_text = '\n'.join(self.convert(ops, opds, sink))
                        print(converted_text)
                        ref.write(converted_text + '\n')
                        statement = ''
                    else:
                        ref.write(statement + '\n')
                        statement = ''

                    # input()

                else:
                    ref.write(statement)
                    statement = ''
                
    def parse_expression(self, expression):
        operators = []
        operands = []

        i = 0
        word_list = list()
        word = ''
        unclose = 0
        lhs = ''
        rhs = ''

        while i < len(expression):
            if expression[i] == '(':
                unclose += 1
                word_list.append(word + '(')
                word = ''
                print(f"word: {word}")

            elif expression[i] in ['&', '|']:
                print(word_list)
                if lhs != '':
                    operators.append(expression[i])
                    operands.append({'lhs': lhs})
                else:
                    operators.append(expression[i])
                    while '(' in word_list[-1] and ')' in word_list[-1]:
                            lhs = word_list.pop() + lhs
                    if lhs == '':
                        lhs = word
                    operands.append({'lhs': lhs})
                    print(f"lhs: {lhs}")    
                print(f"word: {word}")
                word = ''
            elif expression[i] == ')':
                if i + 1 < len(expression) and expression[i+1] == ')':
                    if len(operands) == 0:     
                        print(f"word: {word}")      
                        word_list[-1] = word_list[-1] + word + ')'
                        print(f'word_list: {word_list}')
                        while '(' in word_list[-1] and ')' in word_list[-1]:
                                rhs = word_list.pop() + rhs
                        print(f'rhs {rhs}')
                        operands.append({'lhs': rhs})
                        word_list.pop()
                    else:
                        if operands[-1].get('lhs') and not operands[-1].get('rhs'):
                            word_list[-1] = word_list[-1] + word + ')'
                            while '(' in word_list[-1] and ')' in word_list[-1]:
                                rhs = word_list.pop() + rhs
                            word_list.pop()
                            operands[-1]['rhs'] = rhs
                            lhs = operands[-1]['lhs'] + ' ' + operators[-1] + ' ' + operands[-1]['rhs']
                            rhs = ''
                        
                    print(word_list)
                    # word_list[-1] = word_list[-1] + word + ')'
                    # while '(' in word_list[-1] and ')' in word_list[-1]:
                    #     rhs = word_list.pop() + rhs
                    #     print(rhs + '   ' + word)
                    unclose -= 1       
                    i += 1

                else:
                    word_list[-1] = word_list[-1] + word + ')'
                    print(f"word: {word}")
                unclose -= 1
                word = ''

            elif expression[i] == ' ':
                pass
            else:
                word += expression[i]
            i += 1

        if len(operators) == 0 and len(operands) == 0:
            operands.append({'lhs': word})

        print(operators)
        print(operands)
        print(word)
        return operators, operands


    def convert(self, ops, oprands, sink = ''):
        i = 0
        leaf_node = [0]
        converted = ['1']

        def insert_node(leaf_node, converted, node, op = ''):
            new_leaf = []
            index = 0
            insert_line = 0
            for leaf_num in leaf_node:
                assign_value = converted.pop(leaf_num + insert_line)
                index = leaf_num + insert_line
                insert_line -= 1
                # print(f"index is: {index}, converted is: {converted}")
                # print(f"new_leaf is: {new_leaf}")
                converted.insert(index, f"if ({node}) {{")
                index += 1
                insert_line += 1
                if op == '':
                    if sink != '':
                        converted.insert(index, f"{sink} = {assign_value};")
                    else:
                        converted.insert(index, assign_value)
                elif op == '&':
                    if sink != '':
                        converted.insert(index, f"{sink} = {assign_value};")
                    else:
                        converted.insert(index, assign_value)
                elif op == '|':
                    if sink != '':
                        converted.insert(index, f"{sink} = 1;")
                    else:
                        converted.insert(index, '1')
                else:
                    print(f"[OPWARNING] {op} is not legal")
                new_leaf.append(index)

                index += 1
                insert_line += 1
                converted.insert(index, "}")
                index += 1
                insert_line += 1
                converted.insert(index, "else {")
                index += 1
                insert_line += 1
                if op == '':
                    if sink != '':
                        converted.insert(index, f"{sink} = {str(1 - int(assign_value))};")
                    else:
                        converted.insert(index, str(1 - int(assign_value)))
                elif op == '&':
                    if sink != '':
                        converted.insert(index, f"{sink} = 0;")
                    else:
                        converted.insert(index, '0')
                elif op == '|':
                    if sink != '':
                        converted.insert(index, f"{sink} = {assign_value};")
                    else:
                        converted.insert(index, assign_value)
                else:
                    print(f"[OPWARNING] {op} is not legal")
                new_leaf.append(index)
                

                index += 1
                insert_line += 1
                converted.insert(index, "}")
                index += 1
                insert_line += 1
                # print(f"index is: {index}, converted is: {converted}")
            
            leaf_node.clear()
            leaf_node.extend(new_leaf)

            # print(converted)
            # print(f"leaf_node is: {leaf_node}")

        if len(ops) == 0:
            opd = oprands[0]
            insert_node(leaf_node, converted, opd['lhs'])
            print('\n'.join(converted))
            return converted

        while i < len(ops):
            op = ops[i]
            opd = oprands[i]
            if i == 0:
                insert_node(leaf_node, converted, opd['lhs'])

            # print(f"leaf_node is: {leaf_node}")
            insert_node(leaf_node, converted, opd['rhs'], op)
            i += 1
        
        print('\n'.join(converted))
        return converted


test_file = 'VTestHarness_LSU__DepSet_h3bd28679__0.cpp'
write_file = 'rewrite.cpp'
def test():
    simrefact = SIMRefactor(args.vdir, args.sdir)
    simrefact.refactor_module(args.module)
    # simrefact.refactor_cpp(test_file, write_file)

test()


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

cpp_assign = re.compile(r"([^\s]+) += +(.*);")
stmt_terminal = ['{', '}', '//', '#include']

op_words = [' ^ ', ' > ', ' >= ', ' | ', ' & ', ' == ', ' ~ ', ' != ', ' < ', ' <= ', '>>', '<<', '+', '-', '*', '/']
op_match = re.compile(r"^[^\w,\(\)]+$")
opkeyword = set()
oplist = {}
op_w2o = ['>=', '<', '>', '==', '!=', '<=']
logic_op = ['^', '&', '|']

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
            for file in self.refact_file:
                # file = self.refact_file[-1]
                print(file)
                input()
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
            return 0
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
                return 1

        else:
            oplist[len(ops)] = [ops]
            return 1
        return 0
    
    def get_dump(self, sink, dump_file):
        with open(dump_file, 'a+') as f:
            f.write(f"    dfile << \"{sink.split('->')[1]} = \" << std::hex << std::setw(2) << static_cast<uint64_t>(syms->TOP__TestHarness__chiptop__system__tile_prci_domain__tile_reset_domain__boom_tile__lsu.{sink.split('->')[1]}) << std::endl;\n")

    def refactor_cpp(self, orig_file, rewrite_file, expand_sig):
        with open(orig_file, 'r')  as of:
            o_codes = of.readlines()
        with open(rewrite_file, 'w') as ref:
            statement = ''
            for single_line in o_codes:
                if any(char in single_line for char in stmt_terminal):
                    if statement == '':
                        statement += ' ' + single_line
                    else:
                        statement += ' ' + single_line.strip(' ')
                    ref.write(statement)
                    statement = ''
                    continue
                if single_line == '\n':
                    if statement == '':
                        statement += ' ' + single_line
                    else:
                        statement += ' ' + single_line.strip(' ')
                    ref.write(statement)
                    statement = ''
                    continue
                if ';' not in single_line:
                    if statement == '':
                        statement = single_line.strip('\n')
                    else:
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
                    # print(f"all sink is: {sink} {sink.split('__')[-1]}")
                    if sink.split('__')[-1] in expand_sig:
                        # print(f"stmt is: {statement}")
                        print(f"sink is: {sink}, srcs expr is: {src_expr}")
                        # self.get_dump(sink, 'signal.txt')
                        ops, opds = self.parse_expression(src_expr)
                        print(f"ops is: {ops}")
                        ops_update = self.ops_collect(ops)
                        converted_text = '\n'.join(self.convert(ops, opds, sink))
                        print(converted_text)
                        # if ops_update:
                        #     print(f"Change control flow")
                        #     print(converted_text)
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
                    # print(f"op {word}, temp: {temp}, operands: {parse_operands}")
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
                        # print(f"temp: {temp}")
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
                            # print(f"temp: {temp}")

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

    def convert(self, ops, operands, sink = ''):
        i = 0
        leaf_node = [0]
        converted = ['1']

        def rotate_value(leaf_node, converted):
            for leaf_num in leaf_node:
                origal = converted[leaf_num]
                converted[leaf_num] = str(1 ^ int(origal))

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
            # print(f"No ops")
            insert_node(leaf_node, converted, operands[0]['lhs'], level = level)
            # print(f"add node: {operands[0]['lhs']}")
            level += 1

        elif '&' not in ops and '|' not in ops and '^' not in ops:
            node = operands[-1]['lhs'] + ' ' + ops[-1] + ' ' + operands[-1]['rhs']
            insert_node(leaf_node, converted, node, ops[-1], level = level)
            level += 1

        else:
            add_node_op = []
            not_node = []          
            op_result = dict()
            for op_index in range(len(ops)):
                if ops[op_index] in op_w2o:
                    if len(add_node_op) != 0:
                        remove = []
                        for node_index in add_node_op:
                            result = op_result[node_index]
                            if result in operands[op_index]['lhs'] or result in operands[op_index]['rhs']:
                                remove.append(node_index)
                        if len(remove) != 0:
                            for r in remove:
                                op_result.pop(r)
                                add_node_op.remove(r)
       
                elif ops[op_index] == '~':

                    not_node.append(op_index)
                    op_result[op_index] = operands[op_index]['lhs'] + ' ' + ops[op_index] + ' ' + operands[op_index]['rhs']
                elif ops[op_index] in logic_op:
                    add_node_op.append(op_index)
                    op_result[op_index] = operands[op_index]['lhs'] + ' ' + ops[op_index] + ' ' + operands[op_index]['rhs']

            if len(add_node_op) == 0:
                node = operands[-1]['lhs'] + ' ' + ops[-1] + ' ' + operands[-1]['rhs']
                insert_node(leaf_node, converted, node, ops[-1], level = level)
                level += 1
            else:
                print(f"op is: {ops}, add_node_ops is: {add_node_op}, not_node is: {not_node}")
                inserted = 0
                for not_i in range(len(not_node)):
                    if op_result[not_node[not_i]] in operands[add_node_op[0]]['lhs']:
                        insert_node(leaf_node, converted, f"1U & {operands[add_node_op[0]]['lhs']}", level = level)
                        not_node.pop(not_i)
                        inserted = 1
                        break
                if inserted == 0:
                    if operands[add_node_op[0]]['lhs'][:3] != "VL_":
                        insert_node(leaf_node, converted, operands[add_node_op[0]]['lhs'], level = level)
                    else:
                        insert_node(leaf_node, converted, f"1U & {operands[add_node_op[0]]['lhs']}",  level = level)
                # print(f"add node: {operands[add_node_op[0]]['lhs']}")
                level += 1
                node = operands[add_node_op[0]]['lhs']
                for op_index in add_node_op:
                    inserted = 0
                    if node in operands[op_index]['lhs']:
                        for not_i in range(len(not_node)):
                            if op_result[not_node[not_i]] in operands[op_index]['rhs']:
                                insert_node(leaf_node, converted, f"1U & {operands[op_index]['rhs']}", ops[op_index], level)
                                not_node.pop(not_i)
                                inserted = 1
                                break
                        if inserted == 0:
                            if operands[op_index]['rhs'][:3] != "VL_":
                                insert_node(leaf_node, converted, operands[op_index]['rhs'], ops[op_index], level)
                            else:
                                insert_node(leaf_node, converted, f"1U & {operands[op_index]['rhs']}", ops[op_index], level)
                        # print(f"add node: {operands[op_index]['rhs']}, op is: {ops[op_index]}")
                    else:
                        for not_i in range(len(not_node)):
                            if op_result[not_node[not_i]] in operands[op_index]['lhs']:
                                insert_node(leaf_node, converted, f"1U & {operands[op_index]['lhs']}", ops[op_index], level)
                                not_node.pop(not_i)
                                inserted = 1
                                break
                        if inserted == 0:
                            if operands[op_index]['lhs'][:3] != "VL_":
                                insert_node(leaf_node, converted, operands[op_index]['lhs'], ops[op_index], level)
                            else:
                                insert_node(leaf_node, converted, f"1U & {operands[op_index]['lhs']}", ops[op_index], level)
                        # print(f"add node: {operands[op_index]['lhs']}, op is: {ops[op_index]}")
                    level += 1
                    node = operands[op_index]['lhs'] + ' ' + ops[op_index] + ' ' + operands[op_index]['rhs']
                    for not_i in range(len(not_node)):
                        if node in op_result[not_node[not_i]]:
                            rotate_value(leaf_node, converted)
                            not_node.pop(not_i)
                            break

            if len(not_node) != 0:
                print(f"[ERROR] not op does not parse complete!")
        
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


import os
import sys
import re
import argparse
import graphviz
import networkx as nx
import matplotlib.pyplot as plt

parser = argparse.ArgumentParser(
                    prog='gen_cfg.py',
                    description='Regenerate the CFG for verilog',
                    epilog='Writed by Yinshuai')

parser.add_argument('-f', "--file", metavar='N', type=str, default='',
                    help='The verilog file to generate cfg')

regs = dict()
wires = dict()
ports = dict()
conds = dict()
args = parser.parse_args()

temp_get_wire = re.compile(r"(wire)\s*(\[\d+:\d+\])?\s*([^,;\s]+)")
get_port = re.compile(r"(input|output)\s*(\[\d+:\d+\])?\s*([^,\s]+)")
get_vars = re.compile(r"(reg|wire)\s*(\[\d+:\d+\])?\s*([^,\s]+);")
get_cond_assign = re.compile(r"(assign|wire)\s*(\[\d+:\d+\])?\s*([^\s]+) = (.+) \? (.+) : (.+);") 
get_assign = re.compile(r"(assign|wire)\s*(\[\d+:\d+\])?\s*([^,\s]+) = ([^?]+);") 
temp_wires = list()

def parse_verilog(v_file):
    with open(v_file, 'r') as f:
        lines = f.readlines()
    text = ''
    for line in lines:
        temp_wire = temp_get_wire.search(line)
        if temp_wire:
            temp_wires.append(temp_wire.group(3))
        if (('wire' in line and ';' not in line) or (text != '' and  ';' not in line) or ('assign' in line and ';' not in line)):
            text += line.strip()
            continue
        text += ' ' + line.strip()
        port = get_port.search(text)
        var = get_vars.search(text)
        cond = get_cond_assign.search(text)
        assign = get_assign.search(text)
        text = ''
        
        if port:
            width = int(port.group(2).split(':')[0][1:]) - int(port.group(2).split(":")[1][:-1]) + 1 if port.group(2) else 1
            ports[port.group(3)] = {'dir': port.group(1), 'width': width}
            # print(port.group(3), ports[port.group(3)])
        if var:
            width = int(var.group(2).split(':')[0][1:]) - int(var.group(2).split(":")[1][:-1]) + 1 if var.group(2) else 1
            if 'wire' in var.group(1):
                wires[var.group(3)] = {'width': width}

            elif '_RAND' not in var.group(3):
                regs[var.group(3)] = {'width': width}

        if cond:
            if cond.group(1) == 'wire':
                width = int(cond.group(2).split(':')[0][1:]) - int(cond.group(2).split(":")[1][:-1]) + 1 if cond.group(2) else 1
                wires[cond.group(3)] = {'width': width, 'gen': True, 'cond': cond.group(4), 'true': cond.group(5), 'false': cond.group(6)}

            else:
                wires[cond.group(3)] = {'gen': True, 'cond': cond.group(4), 'true': cond.group(5), 'false': cond.group(6)}

            if not conds.get(cond.group(4)):
                conds[cond.group(4)] = (1, [cond.group(3)])

            else:

                conds[cond.group(4)] = (1 + conds[cond.group(4)][0], conds[cond.group(4)][1] + [cond.group(3)])
         # print(conds[cond.group(4)])

        if assign:
            if assign.group(1) == 'wire':
                width = int(assign.group(2).split(':')[0][1:]) - int(assign.group(2).split(":")[1][:-1]) + 1 if assign.group(2) else 1
                wires[assign.group(3)] = {'width': width, 'gen': True, 'expr': assign.group(4)}
                # print(assign.group(3), wires[assign.group(3)])
            else:
                wires[assign.group(3)] = {'expr': assign.group(4)}
                # print(assign.group(3), wires[assign.group(3)])

def get_word(expr):
    split = expr.strip().split(' ')
    words = list()
    if len(split) == 1:
        words += split
    else:
        for word in split:
            real_word = re.search("^(\w*)", word)
            if real_word:
                words.append(real_word.group(1))
    # print(words)
    return words

def unroll_expr(expr):
    words = get_word(expr)
    for word in words:
        if wires.get(word) and wires[word].get('expr'):
            # print('expr ' + wires[word].get('expr'))   
            repl_expr = wires[word].get('expr')
            unroll = unroll_expr(repl_expr)
            new_expr = list()
            for i in expr.split(' '):
                if i == word:
                    new_expr.append(f"( {unroll} )")
                else:
                    new_expr.append(i)
            expr = ' '.join(new_expr)
    return expr 

def regen_cfg(cfg_map):
    combine_cfg = nx.DiGraph()
    node_num = 0
    parsed_node = list()
    for cond, srcs in conds.items():
        # print('cond ' + cond)
        # words = get_word(cond)
        # for word in words:
        #     if wires.get(word) and wires[word].get('expr'):
        #         cond = cond.replace(word, wires[word].get('expr'))
        cond = unroll_expr(cond)
        # print('unroll cond ' + cond)
        cond_text = f'if ({cond})'
        combine_cfg.add_node(node_num)
        cfg_map[node_num] = cond_text
        cond_node = node_num
        node_num += 1
        true_text = list()
        false_text = list()
        # print(srcs)
        for src in srcs[1]:
            # print(wires[src])
            expr_unroll = unroll_expr(wires[src]['true'])
            true_text.append(f"{src} = {expr_unroll}") 
            expr_unroll = unroll_expr(wires[src]['false'])
            false_text.append(f"{src} = {expr_unroll}")
        cfg_map[node_num] = '\n'.join(true_text)
        true_node = node_num
        node_num += 1
        cfg_map[node_num] = '\n'.join(false_text)
        false_node = node_num
        node_num += 1
        combine_cfg.add_edge(cond_node, true_node)
        combine_cfg.add_edge(cond_node, false_node)
        parsed_node += srcs[1]
    expr_node = node_num
    wire_assign_text = list()
    for wire in wires.keys():
        if wire in parsed_node or not wires[wire].get('expr'):
            continue
        # print(wire, wires[wire])
        wire_assign_text.append(f"{wire} = {wires[wire]['expr']}")
        parsed_node.append(wire)
    cfg_map[expr_node] = '\n'.join(wire_assign_text)
    node_num += 1
    # print(parsed_node)
    not_parsed = list(set(wires.keys()) - set(parsed_node))
    if len(not_parsed) != 0:
        print(not_parsed, len(not_parsed))
        print("[Warning] Wire variables don't parse finish!")

    return combine_cfg
    
def draw_cfg(name, cfg, node_map):
    dot = graphviz.Digraph()
    dot.node_attr['shape'] = 'box'
    edges = list()
    for node in cfg.nodes:
        dot.node(str(node), node_map[node])
    for edge in cfg.edges:
        edges.append((str(edge[0]), str(edge[1])))
    dot.edges(edges)
    dot.format = 'svg'
    dot.render(name)


parse_verilog(args.file)
cfg_map = dict()
cfg = regen_cfg(cfg_map)
print(f"graph node num: {len(cfg.nodes)}, edge is: {len(cfg.edges)}")

print(len(wires.keys()), len(temp_wires))
print(set(temp_wires) - set(wires.keys()))
test = dict(filter(lambda x: x[1].get('expr'), wires.items()))
# print(test)
# for cond, srcs in conds.items():
#     print(wires[cond])
# print(cfg.edges())
draw_cfg('lsu_cfg', cfg, cfg_map)
print("Expr wires")
print(cfg_map[max(cfg_map.keys())])

# pos = nx.nx_agraph.graphviz_layout(cfg, prog='dot')
# nx.draw(cfg, pos, with_labels=True, arrows=True)
# plt.show()
import os
import sys
import re
import argparse
import graphviz
import networkx as nx
import matplotlib.pyplot as plt


temp_get_wire = re.compile(r"(wire)\s*(\[\d+:\d+\])?\s*([^,;\s]+)")
get_port = re.compile(r"(input|output)\s*(\[\d+:\d+\])?\s*([^,\s]+)")
get_vars = re.compile(r"(reg|wire)\s*(\[\d+:\d+\])?\s*([^,\s]+);")
get_cond_assign = re.compile(r"(assign|wire)\s*(\[\d+:\d+\])?\s*([^\s]+) = (.+) \? (.+) : (.+);") 
get_assign = re.compile(r"(assign|wire)\s*(\[\d+:\d+\])?\s*([^,\s]+) = ([^?]+);") 

class HDL_CFG:

    def __init__(self, verilog_file) -> None:
        self.v_file = verilog_file
        self.regs = dict()
        self.wires = dict()
        self.ports = dict()
        self.conds = dict()
        self.temp_wires = list()

    def parse_verilog(self):
        with open(self.v_file, 'r') as f:
            lines = f.readlines()
        text = ''
        for line in lines:
            temp_wire = temp_get_wire.search(line)
            if temp_wire:
                self.temp_wires.append(temp_wire.group(3))
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
                self.ports[port.group(3)] = {'dir': port.group(1), 'width': width}
                # print(port.group(3), ports[port.group(3)])
            if var:
                width = int(var.group(2).split(':')[0][1:]) - int(var.group(2).split(":")[1][:-1]) + 1 if var.group(2) else 1
                if 'wire' in var.group(1):
                    # print(f'wire is: {var.group(3)} {width}')
                    self.wires[var.group(3)] = {'width': width}

                elif '_RAND' not in var.group(3):
                    self.regs[var.group(3)] = {'width': width}

            if cond:
                srcs = []
                for i in range(4, 7):
                    # print(cond.group(i))
                    srcs += self.get_word(cond.group(i))
                if cond.group(1) == 'wire':
                    width = int(cond.group(2).split(':')[0][1:]) - int(cond.group(2).split(":")[1][:-1]) + 1 if cond.group(2) else 1
                    
                    # print(f"{cond.group(3)} srcs is: {srcs}")
                    self.wires[cond.group(3)] = {'width': width, 'gen': True, 'cond': cond.group(4), 'true': cond.group(5), 'false': cond.group(6), 'srcs': srcs}

                else:
                    if not self.wires.get(cond.group(3)):
                        self.wires[cond.group(3)] = {'gen': True, 'cond': cond.group(4), 'true': cond.group(5), 'false': cond.group(6), 'srcs': srcs}
                    else:
                        self.wires[cond.group(3)].update({'gen': True, 'cond': cond.group(4), 'true': cond.group(5), 'false': cond.group(6), 'srcs': srcs})

                if not self.conds.get(cond.group(4)):
                    self.conds[cond.group(4)] = (1, [cond.group(3)])

                else:

                    self.conds[cond.group(4)] = (1 + self.conds[cond.group(4)][0], self.conds[cond.group(4)][1] + [cond.group(3)])
            # print(conds[cond.group(4)])

            if assign:
                srcs = self.get_word(assign.group(4))
                if assign.group(1) == 'wire':
                    width = int(assign.group(2).split(':')[0][1:]) - int(assign.group(2).split(":")[1][:-1]) + 1 if assign.group(2) else 1
                    self.wires[assign.group(3)] = {'width': width, 'gen': True, 'expr': assign.group(4), 'srcs': srcs}
                    # print(assign.group(3), wires[assign.group(3)])
                else:
                    if not self.wires.get(assign.group(3)):
                        self.wires[assign.group(3)] = {'expr': assign.group(4), 'srcs': srcs}
                    else:
                        self.wires[assign.group(3)].update({'expr': assign.group(4), 'srcs': srcs})
                    # print(assign.group(3), wires[assign.group(3)])

    def get_word(self, expr):
        split = expr.strip().split(' ')
        words = list()
        for word in split:
            real_word = re.search("^[~|]?([a-zA-Z0-9_]*)$", word)
            if real_word:
                words.append(real_word.group(1))
        # if len(words) != 0:
        #     print(words)    
        return words

    def unroll_expr(self, expr):
        words = self.get_word(expr)
        for word in words:
            if self.wires.get(word) and self.wires[word].get('expr'):
                # print('expr ' + wires[word].get('expr'))   
                # print('unroll ' + word)
                repl_expr = self.wires[word].get('expr')
                # print(f'original expr: {repl_expr}')
                unroll = self.unroll_expr(repl_expr)
                # print(f'unroll expr : {unroll}')
                new_expr = list()
                for i in expr.split(' '):
                    if i == word:
                        new_expr.append(f"( {unroll} )")
                    # ~a, |a etc. operator in the front of the oprand.
                    elif i[1:] == word:
                        new_expr.append(f"{i[0]}( {unroll} )")
                    # TODO a[5:4] the extract syntax need to unroll.
                    else:
                        new_expr.append(i)
                expr = ' '.join(new_expr)
        return expr 

    def regen_cfg(self, cfg_map):
        combine_cfg = nx.DiGraph()
        node_num = 0
        parsed_node = list()
        for cond, srcs in self.conds.items():
            # print('cond ' + cond)
            # words = get_word(cond)
            # for word in words:
            #     if wires.get(word) and wires[word].get('expr'):
            #         cond = cond.replace(word, wires[word].get('expr'))
            cond = self.unroll_expr(cond)
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
                expr_unroll = self.unroll_expr(self.wires[src]['true'])
                true_text.append(f"{src} = {expr_unroll}") 
                expr_unroll = self.unroll_expr(self.wires[src]['false'])
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
        for wire in self.wires.keys():
            if wire in parsed_node or not self.wires[wire].get('expr'):
                continue
            # print(wire, wires[wire])
            wire_assign_text.append(f"{wire} = {self.wires[wire]['expr']}")
            parsed_node.append(wire)
        cfg_map[expr_node] = '\n'.join(wire_assign_text)
        node_num += 1
        not_parsed = list(set(self.wires.keys()) - set(parsed_node))
        if len(not_parsed) != 0:
            print(not_parsed, len(not_parsed))
            print("[Warning] Wire variables don't parse finish!")

        return combine_cfg

    def draw_cfg(self, name, cfg, node_map):
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

    def cf_encode(self, sig, sig_expr):
        pass

    def df_convert_cf(self):
        need_expand = list()
        for sig, info in self.wires.items():
            if info.get('expr'):
                if info.get('width'):
                    if info.get('width') == 1:
                        self.cf_encode(sig, info['expr'])
                        # print(sig)
                        need_expand.append(sig)
                elif self.ports.get(sig):   
                    if self.ports[sig]['width'] == 1:
                        self.cf_encode(sig, info['expr'])
                        need_expand.append(sig)
                        # print(sig)
                else:
                    print(f"{sig} may a register var")
        return need_expand


def test():
    parser = argparse.ArgumentParser(
                    prog='gen_cfg.py',
                    description='Regenerate the CFG for verilog',
                    epilog='Writed by Yinshuai')

    parser.add_argument('-f', "--file", metavar='N', type=str, default='',
                        help='The verilog file to generate cfg')
    
    args = parser.parse_args()
    
    
    lsu_cfg = HDL_CFG(args.file)
    lsu_cfg.parse_verilog()
    cfg_map = dict()
    cfg = lsu_cfg.regen_cfg(cfg_map)
    print(f"graph node num: {len(cfg.nodes)}, edge is: {len(cfg.edges)}")

    print(len(lsu_cfg.wires.keys()), len(lsu_cfg.temp_wires))
    print(set(lsu_cfg.temp_wires) - set(lsu_cfg.wires.keys()))
    test = dict(filter(lambda x: x[1].get('expr'), lsu_cfg.wires.items()))
    # print("Expr is:")
    # print(test)
    # print("-----")
    # for cond, srcs in conds.items():
    #     print(wires[cond])
    # print(cfg.edges())

    ## Dump the cfg.
    lsu_cfg.draw_cfg('lsu_cfg', cfg, cfg_map)
    print("Expr wires")
    for sig, info in lsu_cfg.wires.items():
        if info.get('expr'):
            if info.get('width'):
                print(f"{info['width']} {sig} : {info['expr']}")
            elif lsu_cfg.ports.get(sig):   
                print(f"{lsu_cfg.ports[sig]['width']} {sig} {info['expr']}")  
            else:
                print(f"{sig} may a register var")
            

    # pos = nx.nx_agraph.graphviz_layout(cfg, prog='dot')
    # nx.draw(cfg, pos, with_labels=True, arrows=True)
    # plt.show()

# test()
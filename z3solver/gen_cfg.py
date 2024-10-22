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
        self.src_num = dict()
        self.assyn_num = 0

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
                    self.wires[cond.group(3)] = {'width': width, 'gen': True, 'cond': cond.group(4), 'true': cond.group(5), 'false': cond.group(6), 'srcs': srcs, 'order': self.assyn_num}

                else:
                    if not self.wires.get(cond.group(3)):
                        self.wires[cond.group(3)] = {'gen': True, 'cond': cond.group(4), 'true': cond.group(5), 'false': cond.group(6), 'srcs': srcs, 'order': self.assyn_num}
                        
                    else:
                        self.wires[cond.group(3)].update({'gen': True, 'cond': cond.group(4), 'true': cond.group(5), 'false': cond.group(6), 'srcs': srcs, 'order': self.assyn_num})
                self.assyn_num += 1

                if not self.conds.get(cond.group(4)):
                    self.conds[cond.group(4)] = (1, [cond.group(3)])

                else:

                    self.conds[cond.group(4)] = (1 + self.conds[cond.group(4)][0], self.conds[cond.group(4)][1] + [cond.group(3)])
            # print(conds[cond.group(4)])

            if assign:
                srcs = self.get_word(assign.group(4))
                if assign.group(1) == 'wire':
                    width = int(assign.group(2).split(':')[0][1:]) - int(assign.group(2).split(":")[1][:-1]) + 1 if assign.group(2) else 1
                    self.wires[assign.group(3)] = {'width': width, 'gen': True, 'expr': assign.group(4), 'srcs': srcs, 'order': self.assyn_num}
                    # print(assign.group(3), wires[assign.group(3)])
                else:
                    if not self.wires.get(assign.group(3)):
                        self.wires[assign.group(3)] = {'expr': assign.group(4), 'srcs': srcs, 'order': self.assyn_num}
                    else:
                        self.wires[assign.group(3)].update({'expr': assign.group(4), 'srcs': srcs, 'order': self.assyn_num})
                self.assyn_num += 1
                    # print(assign.group(3), wires[assign.group(3)])
        self.ordered_wires = dict(sorted(self.wires.items(), key=lambda x: x[1].get('order', float('-inf'))))

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
    
    def check_cyclic(self):
        skip_sig = list()
        for sig in self.wires.keys():
            parsed = list()
            not_parsed = [sig]
            while len(not_parsed) != 0:
                s = not_parsed.pop()
                if self.wires.get(s):
                    info = self.wires[s]
                else:
                    continue
                if s in parsed:
                    print(f"[WAR] Find a cyclic in combination logic! Sig is: {s}")
                parsed += s
                if info.get('expr'):
                    expr = info.get('expr')
                    expr_word = self.get_word(expr)
                    # print(f"expr_word is: {list(filter(lambda x: x in self.wires.keys() or x in self.ports.keys(), expr_word))}")
                    not_parsed += list(filter(lambda x: (x in self.wires.keys() or x in self.ports.keys()) and x not in skip_sig, expr_word))
                elif info.get('cond'):
                    tf_word = self.get_word(info.get('true')) + self.get_word(info.get('false'))
                    not_parsed += list(filter(lambda x: (x in self.wires.keys() or x in self.ports.keys()) and x not in skip_sig, tf_word))
                    # print(f"tf_word is: {list(filter(lambda x: x in self.wires.keys() or x in self.ports.keys(), tf_word))}")
            if s not in skip_sig:
                skip_sig.append(s)
            print(f"{s} check finish!")
            skip_sig += list(set(parsed) - set(skip_sig))
    
    def get_reg_cond(self):
        reg_sigs = {'cond': dict(), 'expr': dict()}
        wrie_sigs = {'cond': dict(), 'expr': dict()}
        reg_t = dict()
        for sig, info in self.wires.items():
            if info.get('cond'):
                cond = info.get('cond')
                cond_sig = self.get_word(cond)
                t = 0
                for w in cond_sig:
                    if self.regs.get(w):
                        # print(f"Find reg in expr of cond: {w}")
                        reg_sigs['cond'][sig] = f"{cond} ? {info.get('true')} : {info.get('false')}"
                        t = 1
                        break
                if t == 0:
                    wrie_sigs['cond'][sig] = f"{cond} ? {info.get('true')} : {info.get('false')}"

                for w in self.get_word(info['true']) + self.get_word(info['false']):
                    if self.regs.get(w) and t == 0:
                        reg_t[sig] = f"{cond} ? {info.get('true')} : {info.get('false')}"
                
            elif info.get('expr'):
                expr = info.get('expr')
                expr_sig = self.get_word(expr)
                t = 0
                for w in expr_sig:
                    if self.regs.get(w):
                        # print(f"Find reg in expr: {w}")
                        reg_sigs['expr'][sig] = expr
                        t = 1
                        break
                if t == 0:
                    wrie_sigs['expr'][sig] = expr

        return reg_sigs, wrie_sigs, reg_t


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


def test(dump = False):
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
    srcs_reg, srcs_wires, reg_t = lsu_cfg.get_reg_cond()
    print(f"Register signals in expr src is: {len(srcs_reg['expr'])}")
    for s, e in srcs_reg['expr'].items():
        print(f"{s}: {e}")

    print(f"Register signals in cond src is: {len(srcs_reg['cond'])}")
    for s, e in srcs_reg['cond'].items():
        print(f"{s}: {e}")

    print(f"Wire signals in expr src is: {len(srcs_wires['expr'])}")
    for s, e in srcs_wires['expr'].items():
        print(f"{s}: {e}")

    print(f"Wire signals in cond src is: {len(srcs_wires['cond'])}")
    for s, e in srcs_wires['cond'].items():
        print(f"{s}: {e}")

    print(f"Register signals in true false src is: {len(reg_t)}")
    for s, e in reg_t.items():
        print(f"{s}: {e}")

    lsu_cfg.check_cyclic()

    print("Ordered wires")
    for s, i in lsu_cfg.ordered_wires.items():
        print(f"{s}: {i}")
    # print("Expr is:")
    # print(test)
    # print("-----")
    # for cond, srcs in conds.items():
    #     print(wires[cond])
    # print(cfg.edges())

    ## Dump the cfg.
    if dump:
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

def get_srcunroll():
    test_file = 'module/LSU.v'
    lsu_cfg = HDL_CFG(test_file)
    lsu_cfg.parse_verilog()
    while True:
        target = input()
        

test()
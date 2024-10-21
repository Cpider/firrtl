import re
import os
import struct
import argparse

parser = argparse.ArgumentParser(
                    prog='diffcov.py',
                    description='Compare the edge coverage between two bitmaps',
                    epilog='Writed by Yinshuai')

parser.add_argument('-a', "--amap", metavar='N', type=str, required=True,
                    help='The compared bitmap.')

parser.add_argument('-b', "--bmap", metavar='N', type=str, required=True,
                    help='The compared bitmap.')

parser.add_argument('-d', "--dir", metavar='N', type=str, default='',
                    help='Directory of instrumentation numbers.')

args = parser.parse_args()

bbinfo_dir = args.d if hasattr(args, 'd') and args.d != '' else '/home/yinshuai/firrtl/z3solver/chipyard.TestHarness.SmallBoomAFL' 
# bbinfo_dir = '/home/yinshuai/chipyard/sims/verilator/generated-src/chipyard.TestHarness.SmallBoomConfig/chipyard.TestHarness.SmallBoomAFL'


def print_red(text):
    print('\033[91m[Warning] ' + text + '\033[0m')


def extract_hit(directory):
    with open('%s/fuzz_bitmap' % directory, 'rb') as f:
        bitmap = bytearray(f.read())
        # print(bitmap.hex())
        inverted_bitmap = bytes(~x & 0xff for x in bitmap)
        # print(inverted_bitmap.hex())
        with open('%s/trace_bitmap' % directory, '+wb') as wf:
            wf.write(inverted_bitmap)
        hit_bitmap = bytes(0x00 if i ^ 0x00 ==
                           0x00 else 0x01 for i in inverted_bitmap)
        with open('%s/hit_bitmap' % directory, '+wb') as wf:
            wf.write(hit_bitmap)
        # print(hit_bitmap.hex())


def get_coverage(directory):
    with open('%s/fuzz_trace' % directory, 'rb') as trace_file:
        with open('%s/hit_bitmap' % directory, 'rb') as hit_file:
            hit = list(hit_file.read())
            trace = trace_file.read()
        with open('%s/prev_trace' % directory, 'rb') as prev_file:
            prev = prev_file.read()
    collision = dict()
    total_hit = 0
    hit_overflow = 0
    for index in range(len(hit)):
        collision_num = 0
        if hit[index] == 1:
            # print("index is: ", index)
            total_hit += 1
            curloc = struct.unpack_from(
                '<L', trace, index*4*128+collision_num*4)[0]
            prevloc = struct.unpack_from(
                '<L', prev, index*4*128+collision_num*4)[0]
            if curloc == 0:
                print_red("The hit_bitmap not match with trace_bitmap, index is: %d" % index) if curloc == 0 else print_red(
                    "The hit_bitmap not match with prev_bitmap, index is: %d" % index)
                # return
                continue
            if prevloc == 0:
                print_red(
                    "The hit_bitmap not match with prev_bitmap, index is: %d" % index)
            while struct.unpack_from('<L', trace, index*4*128+(collision_num+1)*4)[0] != 0:
                if prevloc == 0:
                    print_red("The prev_bitmap not match with trace_bitmap, index is:%d, internal index is %d" % (
                        index, collision_num))
                if not collision.get(index):
                    collision[index] = list()
                    collision[index].append(curloc)
                collision_num += 1
                curloc = struct.unpack_from(
                    '<L', trace, index*4*128+collision_num*4)[0]
                prevloc = struct.unpack_from(
                    '<L', prev, index*4*128+collision_num*4)[0]
                collision[index].append(curloc)
        else:
            if struct.unpack_from('<L', trace, index*4*128+collision_num*4)[0] != 0:
                hit_overflow += 1
    # print(collision)
    num = 0
    max_col = 0
    for col in collision.values():
        if len(col) > max_col:
            max_col = len(col)
        num += len(col)
    num -= len(collision.keys())
    print(num)
    print(max_col)
    print_red("Total hit is %d" % total_hit)
    print_red("Hit overflow is %d" % hit_overflow)
    # print(collision.values())


def get_bbnum(bb_names):
    get_module = re.compile('VTestHarness_(.*)\.cpp')
    get_total = re.compile('Total instrument BB numbers is: (\d+)')
    bb_map = dict()
    module_map = dict()
    bb_total = 0
    for bb_name in bb_names:
        with open(bb_name) as bb_file:
            module = ''
            for text in bb_file:
                if get_module.findall(text.strip()):
                    # print(get_module.findall(text.strip())[0])
                    module = get_module.findall(text.strip())[0]
                    if not module_map.get(module):
                        module_map[module] = list()
                    continue
                if get_total.findall(text.strip()):
                    # print(get_total.findall(text.strip())[0])
                    bb_total += int(get_total.findall(text.strip())[0])
                    continue
                # print(f"file: {bb_name} text: {text.strip()}")
                if not bb_map.get(int(text.strip())):
                    bb_map[int(text.strip())] = list()
                bb_map[int(text.strip())].append(module)
                module_map[module].append(int(text.strip()))
    print("Total basic block is: ", bb_total)
    print("Sum is: ", len(bb_map.keys()))
    return bb_map, module_map
    # for i, j in bb_map.items():
    #     if len(j) >= 2:
    #         print(i, j)


def find_bb_files(directory):
    txt_files = []
    for root, dirs, files in os.walk(directory):
        for file in files:
            if file.endswith('.txt'):
                txt_files.append(os.path.join(root, file))
    return txt_files


def diff_bb(dir_a, dir_b):
    bb_a = dict()
    bb_b = dict()
    hit_a = list()
    hit_b = list()
    curloc_a = list()
    curloc_b = list()
    # if not os.path.exists(os.path.join(dir_a, 'hit_bitmap')):
    extract_hit(dir_a)
    # if not os.path.exists(os.path.join(dir_b, 'hit_bitmap')):
    extract_hit(dir_b)
    # Diff the coverage and correspond files and basic block.

    def read_bitmap(directory):
        hit_bitmap = list()
        trace_bitmap = list()
        with open(os.path.join(directory, 'hit_bitmap'), 'rb') as hit_file:
            hit_bitmap = list(hit_file.read())
        with open(os.path.join(directory, 'fuzz_trace'), 'rb') as trace_file:
            trace_bitmap = trace_file.read()
        return hit_bitmap, trace_bitmap
    hit_a, curloc_a, prev_a = read_bitmap(dir_a)
    hit_b, curloc_b, prev_b = read_bitmap(dir_b)
    bb_map, module_map = get_bbnum(find_bb_files(bbinfo_dir))
    # print(len([print(i,j) for i,j in bb_map.items() if len(j)>1]))

    def get_bb(index, curloc_bitmap):
        bb_module = dict()
        bbs = list()
        collision_num = 0
        curloc = struct.unpack_from(
            '<L', curloc_bitmap, index*4*128+(collision_num)*4)[0]
        bbs.append(curloc)
        while struct.unpack_from('<L', curloc_bitmap, index*4*128+(collision_num+1)*4)[0] != 0:
            collision_num += 1
            curloc = struct.unpack_from(
                '<L', curloc_bitmap, index*4*128+collision_num*4)[0]
            bbs.append(curloc)
        # print(bbs)
        for bb in bbs:
            for module in bb_map[bb]:
                for random in module_map[module]:
                    if index ^ bb == random >> 1:
                        if not bb_module.get((random, bb)):
                            bb_module[(random, bb)] = list()
                        bb_module[(random, bb)].append(module)
        return bb_module
    for index in range(len(hit_a)):
        # Check if the hit bit are same.
        if hit_a[index] == 1 and hit_b[index] == 1:
            # TODO:
            a = get_bb(index, curloc_a)
            b = get_bb(index, curloc_b)

            a_index = set(a.keys())
            b_index = set(b.keys())
            for diff in a_index - b_index:
                if not bb_a.get(diff):
                    bb_a[diff] = list()
                bb_a[diff].append(a[diff])
            for diff in b_index - a_index:
                if not bb_b.get(diff):
                    bb_b[diff] = list()
                bb_b[diff].append(b[diff])
        # Check the different with the coverage of a and b
        if hit_a[index] != hit_b[index]:
            bb_a.update(get_bb(index, curloc_a)) if hit_a[index] == 1 else bb_b.update(
                get_bb(index, curloc_b))
    return bb_a, bb_b


def read_bitmap(directory):
    hit_bitmap = list()
    trace_bitmap = list()
    with open(os.path.join(directory, 'hit_bitmap'), 'rb') as hit_file:
        hit_bitmap = list(hit_file.read())
    with open(os.path.join(directory, 'fuzz_trace'), 'rb') as trace_file:
        trace_bitmap = trace_file.read()
    with open(os.path.join(directory, 'prev_trace'), 'rb') as prev_file:
        prev_bitmap = prev_file.read()
    return hit_bitmap, trace_bitmap, prev_bitmap


def get_edge(index, trace_bitmap, prev_bitmap):
    collision_num = 0
    edge = list()
    while struct.unpack_from('<L', trace_bitmap, index*4*128+collision_num*4)[0] != 0:
        edge.append((struct.unpack_from('<L', prev_bitmap, index*4*128+collision_num*4)
                    [0], struct.unpack_from('<L', trace_bitmap, index*4*128+collision_num*4)[0]))
        collision_num += 1
    return edge


def diff_bb_with_prev(dir_a, dir_b):
    bb_a = dict()
    bb_b = dict()
    hit_a = list()
    hit_b = list()
    curloc_a = list()
    curloc_b = list()
    prev_a = list()
    prev_b = list()
    # if not os.path.exists(os.path.join(dir_a, 'hit_bitmap')):
    extract_hit(dir_a)
    # if not os.path.exists(os.path.join(dir_b, 'hit_bitmap')):
    extract_hit(dir_b)
    # Diff the coverage and correspond files and basic block.
    hit_a, curloc_a, prev_a = read_bitmap(dir_a)
    hit_b, curloc_b, prev_b = read_bitmap(dir_b)
    bb_map, module_map = get_bbnum(find_bb_files(bbinfo_dir))
    # print(len([print(i,j) for i,j in bb_map.items() if len(j)>1]))
    index_a = list()
    index_b = list()
    for index in range(len(hit_a)):
        edge_a = get_edge(index, curloc_a, prev_a)
        edge_b = get_edge(index, curloc_b, prev_b)
        if len(edge_b) == 0 and len(edge_a) != 0:
            index_a.append(index)
        if len(edge_a) == 0 and len(edge_b) != 0:
            index_b.append(index)
        for edge in set(edge_a) - set(edge_b):
            module = set(bb_map[edge[0]]) & set(bb_map[edge[1]])
            bb_a[edge] = list(module)
        for edge in set(edge_b) - set(edge_a):
            module = set(bb_map[edge[0]]) & set(bb_map[edge[1]])
            bb_b[edge] = list(module)
    print("get_edge")
    print("Edge only in a is: ", index_a, len(index_a))
    print("Edge only in b is: ", index_b, len(index_b))
    # print(get_edge(6365, curloc_a, prev_a))
    # print(get_edge(6365, curloc_b, prev_b))
    return bb_a, bb_b


def hit_diff(dir_a, dir_b):
    hit_a = read_bitmap(dir_a)[0]
    hit_b = read_bitmap(dir_b)[0]
    only_a = 0
    index_a = list()
    index_b = list()
    total_a = 0
    only_b = 0
    total_b = 0
    for i in range(len(hit_a)):
        if hit_a[i] == 1:
            total_a += 1
            if hit_b[i] == 0:
                only_a += 1
                index_a.append(i)
        if hit_b[i] == 1:
            total_b += 1
            if hit_a[i] == 0:
                only_b += 1
                index_b.append(i)
    print("hit_diff")
    print("hit only in a is:", index_a, len(index_a))
    print("hit only in b is:", index_b, len(index_b))
    return (total_a, only_a), (total_b, only_b)


def main():
    # directory = 'tlb_teston'
    # extract_hit(directory)
    # get_coverage(directory)
    # hit_bitmap, trace_bitmap, prev_bitmap = read_bitmap(directory)
    # bbinfo_dir = '/home/yinshuai/chipyard/sims/verilator/generated-src/chipyard.TestHarness.SmallBoomConfig/chipyard.TestHarness.SmallBoomConfig'
    bb_files = find_bb_files(bbinfo_dir)
    # print(len(bb_files))
    bb_map, module_map = get_bbnum(bb_files)
    # print(bb_map[0])
    # bb_a, bb_b = diff_bb('tlb_testo', 'tlb_test1o')

    
    a_dir = args.amap
    b_dir = args.bmap


    bb_a, bb_b = diff_bb_with_prev(a_dir, b_dir)
    hit_a, hit_b = hit_diff(a_dir, b_dir)
    a_modules = list()
    b_modules = list()
    for i, j in bb_a.items():
        a_modules += j
    for i, j in bb_b.items():
        b_modules += j
    print(set(a_modules)-set(b_modules))
    print("Modules only in a is: ", len(set(a_modules)))
    print("Edge only in a is: ", len(bb_a))
    print_red("Hit toal in a is: %d" % hit_a[0])
    print_red("Hit only in a is: %d" % hit_a[1])
    print(set(b_modules)-set(a_modules))
    print("Modules only in b is: ", len(set(b_modules)))
    print("Edge only in b is: ", len(bb_b))
    print_red("Hit total in b is: %d" % hit_b[0])
    print_red("Hit only in b is: %d" % hit_b[1])


if __name__ == '__main__':
    main()

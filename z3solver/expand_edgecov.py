import re
import argparse

parser = argparse.ArgumentParser(
                    prog='expand_edgecov.py',
                    description='Rewrite the simulator c++ code, encode the data flow to control flow.',
                    epilog='Writed by Yinshuai')

parser.add_argument('-v', "--vdir", metavar='N', type=str, default='',
                    help='The directory of verilog files.')

parser.add_argument('-s', "--sdir", metavar='N', type=str, default='',
                    help='The directory of verilated cpp files.')

args = parser.parse_args()

cpp_assign = re.compile(r"([^\s]+) = (.*);")
stmt_terminal = ['{', '}', '//', '#include']

class SIMRefactor:
    def __init__(self, vdir = '', sdir = ''):
        self.vdir = vdir
        self.sdir = sdir
        
    

    def refactor_cpp(self, orig_file, rewrite_file):
        with open(orig_file, 'r')  as of:
            o_codes = of.readlines()
        with open(rewrite_file, 'w') as ref:
            statement = ''
            for single_line in o_codes:
                if any(char in single_line for char in stmt_terminal):
                    # ref.write(single_line)
                    continue
                if ';' not in single_line:
                    statement += single_line.strip()
                    continue
                statement += single_line.strip()
                # print(f"stmt is: {statement}")
                assign_stmt = cpp_assign.search(statement)
                if assign_stmt:
                    # print(f"stmt is: {statement}")
                    sink = assign_stmt.group(1)
                    src_expr = assign_stmt.group(2)
                    print(f"sink is: {sink}, srcs expr is: {src_expr}")


                statement = ''



test_file = 'VTestHarness_LSU__DepSet_h3bd28679__0.cpp'
write_file = 'rewrite.cpp'
def test():
    simrefact = SIMRefactor(args.vdir, args.sdir)
    simrefact.refactor_cpp(test_file, write_file)

test()


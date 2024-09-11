from z3 import *
import re
import math
from parselog import *

problem = '''and(and(mux(reset, UInt<1>("h0"), and(and(or(or(or(or(or(or(or(or(failed_loads_0, mux(and(and(_T_214, mux(_T_829, UInt<1>("h0"), mux(commit_store, _GEN_13735, mux(commit_load, _GEN_13817, _GEN_13735)))), eq(shr(lcam_addr_0, 6), _block_addr_matches_T_4)), UInt<1>("h0"), mux(_T_228, or(not(mux(commit_store, mux(and(dmem_resp_fired_0, wb_forward_valid_0), mux(dis_ld_val, _GEN_713, ldq_1_bits_forward_std_val), mux(and(not(dmem_resp_fired_0), wb_forward_valid_0), mux(_T_687, or(_GEN_15701, _GEN_2777), _GEN_2777), _GEN_2777)), mux(commit_load, mux(eq(UInt<3>("h1"), idx), UInt<1>("h0"), _GEN_13630), _GEN_13630))), and(neq(mux(and(wb_forward_valid_0, eq(mux(and(and(io_core_exe_0_req_valid, io_core_exe_0_req_bits_uop_ctrl_is_load), _fired_load_incoming_T), mem_incoming_uop_0_ldq_idx, mux(fired_load_wakeup_REG, bits(mux(ldq_wakeup_idx_temp_vec_0, UInt<4>("h0"), _ldq_wakeup_idx_idx_T_13), 2, 0), _lcam_ldq_idx_T)), UInt<3>("h1"))), wb_forward_stq_idx_0, mux(_T_669, ldq_1_bits_forward_stq_idx, mux(_T_671, mux(_T_687, _GEN_13218, ldq_1_bits_forward_stq_idx), ldq_1_bits_forward_stq_idx))), lcam_stq_idx_0), xor(xor(_forwarded_is_older_T_4, _forwarded_is_older_T_5), lt(lcam_stq_idx_0, mux(dis_ld_val, _GEN_657, ldq_1_bits_youngest_stq_idx))))), and(and(_T_237, orr(_mask_match_T_2)), _GEN_8881)))), mux(and(and(_T_250, mux(_T_829, UInt<1>("h0"), mux(commit_store, mux(ldq_2_valid, _GEN_13737, mux(_T_141, _GEN_8383, mux(dis_ld_val, _GEN_674, ldq_2_bits_addr_valid))), mux(commit_load, mux(eq(UInt<3>("h2"), idx), UInt<1>("h0"), _GEN_13740), _GEN_13740)))), eq(_block_addr_matches_T, shr(mux(_T_141, mux(eq(UInt<3>("h2"), ldq_idx), _ldq_bits_addr_bits_T, ldq_2_bits_addr_bits), ldq_2_bits_addr_bits), 6))), UInt<1>("h0"), _GEN_8986)), mux(and(and(and(and(io_dmem_release_valid, not(_will_fire_std_incoming_0_will_fire_T)), mux(or(reset, io_core_exception), UInt<1>("h0"), mux(and(io_core_commit_valids_0, io_core_commit_uops_0_uses_stq), mux(ldq_3_valid, mux(neq(_T_782, UInt<8>("h0")), UInt<1>("h0"), _GEN_2083), mux(and(and(io_core_dis_uops_0_valid, io_core_dis_uops_0_bits_uses_ldq), not(io_core_dis_uops_0_bits_exception)), _GEN_19, ldq_3_valid)), mux(and(io_core_commit_valids_0, io_core_commit_uops_0_uses_ldq), mux(eq(UInt<3>("h3"), idx), UInt<1>("h0"), _GEN_13744), _GEN_13744)))), mux(_T_829, UInt<1>("h0"), mux(commit_store, _GEN_13745, mux(commit_load, _GEN_13819, _GEN_13745)))), eq(_block_addr_matches_T, shr(ldq_3_bits_addr_bits, 6))), UInt<1>("h0"), mux(and(and(and(and(_T_293, not(mux(_T_141, mux(eq(UInt<3>("h3"), ldq_idx), and(dtlb.io_req_0_valid, dtlb.io_resp_0_miss), ldq_3_bits_addr_is_virtual), ldq_3_bits_addr_is_virtual))), bits(dshr(mux(dis_ld_val, mux(eq(UInt<3>("h3"), ldq_tail), next_live_store_mask, _GEN_11), _GEN_11), lcam_stq_idx_0), 0, 0)), and(block_addr_matches_3_0, eq(_dword_addr_matches_T, _dword_addr_matches_T_13))), orr(and(bits(mux(_l_mask_mask_T_45, dshl(UInt<8>("h1"), bits(ldq_3_bits_addr_bits, 2, 0)), mux(_l_mask_mask_T_48, _l_mask_mask_T_51, pad(_l_mask_mask_T_57, 15))), 7, 0), bits(mux(eq(lcam_uop_0_mem_size, UInt<2>("h0")), _lcam_mask_mask_T_2, mux(eq(lcam_uop_0_mem_size, UInt<2>("h1")), _lcam_mask_mask_T_6, pad(_lcam_mask_mask_T_12, 15))), 7, 0)))), or(not(mux(commit_store, _GEN_13632, mux(commit_load, mux(eq(UInt<3>("h3"), mux(commit_store, mux(_T_829, _GEN_14260, mux(commit_store, _T_803, stq_commit_head)), mux(_T_829, UInt<3>("h0"), mux(commit_load, tail(_T_805, 1), ldq_head)))), UInt<1>("h0"), _GEN_13632), _GEN_13632))), and(neq(mux(and(and(_T_623, _T_624), eq(wb_forward_ldq_idx_0, UInt<3>("h3"))), wb_forward_stq_idx_0, mux(_T_669, ldq_3_bits_forward_stq_idx, _GEN_13446)), lcam_stq_idx_0), forwarded_is_older_3)), and(and(_T_309, mask_overlap_3_0), _GEN_9043)))), mux(and(_T_323, block_addr_matches_4_0), UInt<1>("h0"), mux(and(_T_335, orr(and(l_mask_4, lcam_mask_mask))), or(_T_337, and(neq(l_forward_stq_idx_4, mux(or(and(and(and(and(and(io_core_exe_0_req_valid, io_core_exe_0_req_bits_uop_ctrl_is_sta), io_core_exe_0_req_bits_uop_ctrl_is_std), _will_fire_stad_incoming_0_will_fire_T_2), _will_fire_stad_incoming_0_will_fire_T_2), not(exe_req_killed_0)), fired_sta_incoming_REG), io_core_exe_0_req_bits_uop_stq_idx, _lcam_stq_idx_T_1)), xor(xor(lt(l_forward_stq_idx_4, lcam_stq_idx_0), lt(l_forward_stq_idx_4, ldq_4_bits_youngest_stq_idx)), lt(lcam_stq_idx_0, mux(dis_ld_val, mux(eq(UInt<3>("h4"), mux(_T_829, UInt<3>("h0"), mux(and(io_core_brupdate_b2_mispredict, _mem_xcpt_valids_T_5), io_core_brupdate_b2_uop_ldq_idx, mux(dis_ld_val, tail(add(ldq_tail, UInt<3>("h1")), 1), ldq_tail)))), mux(_T_829, mux(reset, UInt<3>("h0"), stq_commit_head), mux(_T_793, io_core_brupdate_b2_uop_stq_idx, mux(dis_st_val, _T_12, stq_tail))), ldq_4_bits_youngest_stq_idx), ldq_4_bits_youngest_stq_idx))))), and(and(and(and(and(and(or(and(or(fired_load_incoming_REG, and(will_fire_load_retry_0_will_fire, not(_fired_load_retry_T_1))), _clr_bsy_valid_0_T), fired_load_wakeup_REG), mux(_T_829, UInt<1>("h0"), _GEN_13916)), mux(_T_829, UInt<1>("h0"), _GEN_13924)), not(ldq_4_bits_addr_is_virtual)), and(eq(_block_addr_matches_T, _block_addr_matches_T_13), eq(bits(lcam_addr_0, 5, 3), bits(mux(_T_141, mux(eq(UInt<3>("h4"), ldq_idx), _ldq_bits_addr_bits_T, ldq_4_bits_addr_bits), ldq_4_bits_addr_bits), 5, 3)))), mask_overlap_4_0), _GEN_9124)))), failed_loads_5), mux(and(and(_T_394, mux(_T_829, UInt<1>("h0"), _GEN_13926)), block_addr_matches_6_0), UInt<1>("h0"), mux(and(_T_407, mask_overlap_6_0), or(not(ldq_6_bits_forward_std_val), _T_411), and(and(_T_417, mask_overlap_6_0), and(xor(_searcher_is_older_T_26, lt(UInt<3>("h6"), ldq_head)), and(_T_422, ldq_6_bits_observed)))))), mux(and(and(_T_430, ldq_7_bits_addr_valid), block_addr_matches_7_0), UInt<1>("h0"), mux(and(and(and(_T_439, bits(dshr(mux(dis_ld_val, _GEN_671, _GEN_15), lcam_stq_idx_0), 0, 0)), dword_addr_matches_7_0), orr(and(bits(mux(eq(ldq_7_bits_uop_mem_size, UInt<2>("h0")), dshl(UInt<8>("h1"), _l_mask_mask_T_106), _l_mask_mask_T_118), 7, 0), lcam_mask_mask))), _T_448, and(and(and(and(and(and(do_ld_search_0, mux(_T_829, UInt<1>("h0"), mux(commit_store, mux(ldq_7_valid, _GEN_13761, mux(dis_ld_val, or(eq(UInt<3>("h7"), ldq_tail), ldq_7_valid), ldq_7_valid)), mux(commit_load, mux(eq(UInt<3>("h7"), idx), UInt<1>("h0"), _GEN_13764), _GEN_13764)))), mux(_T_829, UInt<1>("h0"), mux(commit_store, mux(ldq_7_valid, mux(_T_791, UInt<1>("h0"), _GEN_8428), _GEN_8428), mux(commit_load, mux(eq(UInt<3>("h7"), idx), UInt<1>("h0"), _GEN_13765), _GEN_13765)))), not(mux(_T_141, mux(eq(UInt<3>("h7"), ldq_idx), exe_tlb_miss_0, ldq_7_bits_addr_is_virtual), ldq_7_bits_addr_is_virtual))), and(block_addr_matches_7_0, eq(_dword_addr_matches_T, bits(ldq_7_bits_addr_bits, 5, 3)))), mask_overlap_7_0), and(xor(lt(mux(fired_load_incoming_REG, mem_incoming_uop_0_ldq_idx, _lcam_ldq_idx_T_1), UInt<3>("h7")), lt(lcam_ldq_idx_0, ldq_head)), and(and(_T_436, not(mux(can_fire_load_incoming_0, and(_GEN_5300, dmem_req_fire_0), mux(will_fire_load_retry_0_will_fire, _GEN_6683, mux(and(and(and(_can_fire_store_commit_T_3, not(mux(eq(UInt<3>("h7"), mux(_T_829, mux(reset, UInt<3>("h0"), mux(and(_T_808, mux(_GEN_13983, io_dmem_ordered, mux(eq(UInt<3>("h7"), stq_head), stq_7_bits_succeeded, mux(eq(UInt<3>("h6"), stq_head), mux(clear_store, _GEN_14027, mux(io_dmem_resp_0_valid, _GEN_12340, _GEN_8377)), _GEN_13991)))), mux(_GEN_13983, tail(_stq_execute_head_T, 1), mux(io_dmem_nack_0_valid, _GEN_10653, mux(can_fire_load_incoming_0, stq_execute_head, _GEN_8269))), _GEN_10670)), _GEN_14079)), stq_7_bits_uop_exception, mux(eq(UInt<3>("h6"), stq_execute_head), mux(_T_829, mux(reset, UInt<1>("h0"), _GEN_6666), _GEN_6666), mux(eq(UInt<3>("h5"), stq_execute_head), mux(_T_829, mux(reset, UInt<1>("h0"), _GEN_6665), mux(mem_xcpt_valids_0, mux(mem_xcpt_uops_0_uses_ldq, _GEN_3165, or(eq(UInt<3>("h5"), mem_xcpt_uops_0_stq_idx), _GEN_3165)), mux(dis_ld_val, stq_5_bits_uop_exception, mux(dis_st_val, mux(eq(UInt<3>("h5"), stq_tail), io_core_dis_uops_0_bits_exception, stq_5_bits_uop_exception), stq_5_bits_uop_exception)))), mux(eq(UInt<3>("h4"), stq_execute_head), mux(_T_829, _GEN_14639, mux(mem_xcpt_valids_0, mux(mem_xcpt_uops_0_uses_ldq, _GEN_3164, or(eq(UInt<3>("h4"), mux(_exe_tlb_uop_T_2, io_core_exe_0_req_bits_uop_stq_idx, _exe_tlb_uop_T_5_stq_idx)), _GEN_3164)), _GEN_3164)), mux(eq(UInt<3>("h3"), stq_execute_head), mux(_T_829, mux(reset, UInt<1>("h0"), mux(mem_xcpt_valids_0, mux(mem_xcpt_uops_0_uses_ldq, mux(dis_ld_val, stq_3_bits_uop_exception, _GEN_1787), _GEN_6631), _GEN_3163)), _GEN_6663), mux(eq(UInt<3>("h2"), stq_execute_head), mux(_T_829, mux(reset, UInt<1>("h0"), _GEN_6662), mux(mem_xcpt_valids_0, _GEN_6646, mux(dis_ld_val, stq_2_bits_uop_exception, mux(dis_st_val, mux(eq(UInt<3>("h2"), stq_tail), io_core_dis_uops_0_bits_exception, stq_2_bits_uop_exception), stq_2_bits_uop_exception)))), mux(eq(UInt<3>("h1"), stq_execute_head), mux(_T_829, mux(reset, UInt<1>("h0"), _GEN_6661), mux(mem_xcpt_valids_0, mux(mem_xcpt_uops_0_uses_ldq, mux(dis_ld_val, stq_1_bits_uop_exception, mux(dis_st_val, mux(eq(UInt<3>("h1"), stq_tail), io_core_dis_uops_0_bits_exception, stq_1_bits_uop_exception), stq_1_bits_uop_exception)), _GEN_6629), _GEN_3161)), mux(_T_829, mux(reset, UInt<1>("h0"), _GEN_6660), _GEN_6660)))))))))), _can_fire_store_commit_T_11), not(_will_fire_store_commit_0_will_fire_T_8)), UInt<1>("h0"), and(will_fire_load_wakeup_0_will_fire, and(eq(UInt<3>("h7"), ldq_wakeup_idx), dmem_req_fire_0))))))), or(_T_432, mux(dis_ld_val, mux(eq(UInt<3>("h7"), ldq_tail), UInt<1>("h0"), ldq_7_bits_observed), ldq_7_bits_observed)))))))), and(and(or(or(_mem_xcpt_valids_T_2, and(can_fire_load_incoming_0, io_core_exe_0_req_bits_mxcpt_valid)), ma_st_0), _mem_xcpt_valids_T_5), not(neq(and(io_core_brupdate_b1_mispredict_mask, mux(or(or(_T_43, and(_will_fire_sta_incoming_0_will_fire_T_7, not(_will_fire_sta_incoming_0_will_fire_T_12))), and(and(can_fire_sfence_0, _will_fire_sfence_0_will_fire_T_2), not(not(and(and(not(will_fire_stad_incoming_0_will_fire), not(will_fire_sta_incoming_0_will_fire)), not(and(and(and(io_core_exe_0_req_valid, io_core_exe_0_req_bits_uop_ctrl_is_std), not(io_core_exe_0_req_bits_uop_ctrl_is_sta)), not(not(_will_fire_sta_incoming_0_T_11))))))))), io_core_exe_0_req_bits_uop_br_mask, mux(and(and(and(and(_can_fire_load_retry_T_12, _can_fire_load_retry_T_14), _will_fire_load_retry_0_will_fire_T_2), not(not(_will_fire_release_0_T_5))), not(not(and(and(not(can_fire_load_incoming_0), not(and(_will_fire_hella_incoming_0_will_fire_T_3, _will_fire_stad_incoming_0_will_fire_T_2))), not(will_fire_hella_wakeup_0_will_fire))))), mux(eq(UInt<3>("h7"), bits(mux(ldq_retry_idx_temp_vec_0, UInt<4>("h0"), mux(ldq_retry_idx_temp_vec_1, UInt<4>("h1"), mux(and(and(_ldq_retry_idx_T_6, not(or(block_load_mask_2, mux(will_fire_load_wakeup_0_will_fire, _GEN_5287, _GEN_5319)))), _ldq_retry_idx_temp_vec_T_2), UInt<4>("h2"), mux(ldq_retry_idx_temp_vec_3, UInt<4>("h3"), mux(ldq_retry_idx_temp_vec_4, UInt<4>("h4"), mux(and(and(and(mux(_T_829, UInt<1>("h0"), mux(commit_store, mux(ldq_5_valid, _GEN_13752, mux(_T_141, _GEN_8386, _GEN_2741)), mux(commit_load, _GEN_13821, _GEN_13755))), ldq_5_bits_addr_is_virtual), _ldq_retry_idx_T_16), geq(UInt<3>("h5"), ldq_head)), UInt<4>("h5"), mux(and(_ldq_retry_idx_T_20, _ldq_retry_idx_temp_vec_T_6), UInt<4>("h6"), mux(ldq_retry_idx_temp_vec_7, UInt<4>("h7"), _ldq_retry_idx_idx_T_6)))))))), 2, 0)), ldq_7_bits_uop_br_mask, mux(eq(UInt<3>("h6"), ldq_retry_idx), mux(mux(_T_829, UInt<1>("h0"), mux(commit_store, mux(ldq_6_valid, mux(_T_789, UInt<1>("h0"), _GEN_2086), mux(dis_ld_val, _GEN_22, ldq_6_valid)), _GEN_13862)), and(ldq_6_bits_uop_br_mask, not(io_core_brupdate_b1_resolve_mask)), mux(dis_ld_val, _GEN_222, ldq_6_bits_uop_br_mask)), mux(eq(UInt<3>("h5"), ldq_retry_idx), mux(ldq_5_valid, _ldq_5_bits_uop_br_mask_T_1, mux(dis_ld_val, mux(eq(UInt<3>("h5"), ldq_tail), io_core_dis_uops_0_bits_br_mask, ldq_5_bits_uop_br_mask), ldq_5_bits_uop_br_mask)), _GEN_6305))), mux(and(and(and(and(and(_can_fire_sta_retry_T, mem_stq_retry_e_out_bits_addr_is_virtual), dtlb.io_miss_rdy), _will_fire_sta_retry_0_will_fire_T_2), not(not(and(_will_fire_release_0_T_5, not(will_fire_load_retry_0_will_fire))))), not(not(_will_fire_sfence_0_T_11))), mux(eq(UInt<3>("h7"), bits(mux(and(and(stq_0_bits_addr_valid, stq_0_bits_addr_is_virtual), _stq_retry_idx_temp_vec_T), UInt<4>("h0"), _stq_retry_idx_idx_T_13), 2, 0)), mux(_T_829, mux(reset, UInt<8>("h0"), _GEN_13721), mux(mux(_T_829, mux(reset, UInt<1>("h0"), mux(_T_854, UInt<1>("h0"), _GEN_14045)), _GEN_14045), and(stq_7_bits_uop_br_mask, _mem_xcpt_uops_out_br_mask_T), mux(dis_ld_val, stq_7_bits_uop_br_mask, mux(dis_st_val, mux(eq(UInt<3>("h7"), stq_tail), io_core_dis_uops_0_bits_br_mask, stq_7_bits_uop_br_mask), stq_7_bits_uop_br_mask)))), mux(eq(UInt<3>("h6"), stq_retry_idx), stq_6_bits_uop_br_mask, _GEN_5752)), UInt<8>("h0"))))), UInt<8>("h0"))))), _mem_xcpt_valids_T_5), not(neq(and(io_core_brupdate_b1_mispredict_mask, mux(or(and(mem_xcpt_valids_0, xor(_use_mem_xcpt_T_2, lt(_GEN_9970, io_core_rob_head_idx))), not(ld_xcpt_valid)), and(exe_tlb_uop_0_br_mask, _mem_xcpt_uops_out_br_mask_T), _GEN_10541)), UInt<8>("h0"))))), not(io_core_exception)), not(neq(and(io_core_brupdate_b1_mispredict_mask, and(xcpt_uop_br_mask, _mem_xcpt_uops_out_br_mask_T)), UInt<8>("h0")))) '''


OPERATION = [op.lower() for op in ["Addw", "Dshlw", "Add", "And", "Andr", "AsAsyncReset", "AsClock", "AsFixedPoint", "AsInterval", "AsSInt", "AsUInt", "Bits", "Cat", "Clip", "Cvt", "DecP", "Div", "Dshl", "Dshr", "Eq", "Geq", "Gt", "Head", "IncP", "Leq", "Lt", "Mul", "Neg", "Neq", "Not", "Or", "Orr", "Pad", "Rem", "SetP", "Shl", "Shr", "Squeeze", "Sub", "Tail", "Wrap", "Xor", "Xorr", "Subw", 'Mux']]

# z3 boolref does't cast to bitvecref
LOGICOP = [op.lower() for op in ["Eq", "Geq", "Gt", "Leq", "Lt", "Neq"]]
ARITHOP = [op.lower() for op in ["Mul", "Div", "Add", "Sub", "Rem"]]

get_width_value = re.compile(r'[US]Int<(\d+)>\("\w(\d+)"\)')
int_word = re.compile(r'[US]Int<\d+>\("\w\d+"$')

Op_map = {
    **{'not': {'op': "~", 'oprands': 1, }},
    **{'and': {'op': "&", 'oprands': 2, }},
    **{'andr': {'op': "BVRedAnd", 'oprands': 1, }},
    **{'or': {'op': "|", 'oprands': 2, }},
    **{'orr': {'op': "BVRedOr", 'oprands': 1, }},
    **{'xor': {'op': "^", 'oprands': 2, }},
    # May need get width of the oprand.
    **{'xorr': {'op': "xorr", 'oprands': 1, }},
    **{'bits': {'op': "Extract", 'oprands': 3, }},
    **{'cat': {'op': "Concat", 'oprands': 2, }},
    # Signed extend for SInt, zero extend for UInt. Need to know the type of the oprand
    **{'pad': {'op': "ZeroExt", 'oprands': 2, }},
    **{'sub': {'op': "-", 'oprands': 2, }},
    **{'mux': {'op': "If", 'oprands': 3, }},
    **{'add': {'op': "+", 'oprands': 2, }},
    **{'div': {'op': "/", 'oprands': 2, }},
    **{'rem': {'op': "%", 'oprands': 2, }},
    **{'shr': {'op': "LShR", 'oprands': 2, }},
    # Like zero extend in tail
    **{'shl': {'op': "Concat", 'oprands': 2, }},
    **{'dshl': {'op': "<<", 'oprands': 2, }},
    **{'dshr': {'op': ">>", 'oprands': 2, }},
    **{'gt': {'op': ">", 'oprands': 2, }},
    **{'eq': {'op': "==", 'oprands': 2, }},
    **{'geq': {'op': ">=", 'oprands': 2, }},
    **{'leq': {'op': "<=", 'oprands': 2, }},
    **{'lt': {'op': "<", 'oprands': 2, }},
    **{'mul': {'op': "*", 'oprands': 2, }},
    **{'neg': {'op': "~", 'oprands': 2, }},
    **{'neq': {'op': "!=", 'oprands': 2, }},
    **{'tail': {'op': "Extract", 'oprands': 2, }},

    **{'assint': {'op': "Extract", 'oprands': 1, }},
    **{'asuint': {'op': "Extract", 'oprands': 1, }},
}

z3head = '''
from z3 import *

'''

z3constraint = '''

solver = Solver()
solver.add(testio == BitVecVal(1, 1))
solver.add(io_core_exe_0_req_valid == BitVecVal(1, 1))

'''

z3tail = '''

check = solver.check()
model = solver.model()
print(check)
for decl in model.decls():
    print("{} = {}".format(decl.name(), model[decl]))

'''

class Z3Solver:

    def __init__(self, pre: Preprocess) -> None:
        self.pre = pre
        self.sigdef = dict()
        self.tmpsig = dict()
        self.tn = 0
        self.z3expr = list()
        self.custom_constraint = list()

    def redxor(self, value, width):
        return "Extract(0, 0," + '^'.join([f'({value}^({value} >> {2**i}))' for i in range(int(math.log(width, 2)))]) + ")"
    
    def bool2bv(self, expr):
        return f"If({expr}, BitVecVal(1, 1), BitVecVal(0, 1))"
    
    def bv2bool(self, expr):
        return f"({expr} == BitVecVal(1, 1))"
    
    def gen_tmp(self):
        tmp = f'tmp_{self.tn}'
        self.tn += 1
        return tmp
    
    def get_width_type(self, module, sig):
        if self.tmpsig.get(sig):
            return self.tmpsig.get(sig)
        if self.pre.signal_width[module].get(sig.replace('__', '.')):
            return self.pre.signal_width[module].get(sig.replace('__', '.'))
        bitvecre = re.compile(r"BitVecVal\(\d+, (\d+)\)")
        width = bitvecre.search(sig)
        if width:
            return (str(width.group(1)), 'UInt')
        ## If type of variable is not UInt or SInt, this variable may not include in signal_width, if find this sig, will return None
        else:
            None

    def align_width(self, o0, o1):
        o0_width, o0_ty, o0_name = o0
        o1_width, o1_ty, o1_name = o1
        o0_new = o0_name
        o1_new = o1_name
        if int(o0_width) < int(o1_width):
            n = int(o1_width) - int(o0_width)
            if o0_ty == 'UInt':
                o0_new =  f"ZeroExt({n}, {o0_name})"
            if o0_ty == 'SInt':
                o0_new = f"If(Extract({int(o0_width) - 1}, {int(o0_width) - 1}, {o0_name}) == 1, SignExt({str(n)}, {o0_name}), ZeroExt({str(n)}, {o0_name}))"
            o0_width = o1_width
            
        else:
            n = int(o0_width) - int(o1_width)
            if o1_ty == 'UInt':
                o1_new = f"ZeroExt({n}, {o1_name})"
            if o1_ty == 'SInt':
                o1_new = f"If(Extract({int(o1_width) - 1}, {int(o1_width) - 1}, {o1_name}) == 1, SignExt({str(n)}, {o1_name}), ZeroExt({str(n)}, {o1_name}))"
            o1_width = o0_width

        return ((o0_width, o0_ty, o0_new), (o1_width, o1_ty, o1_new))


    def parse_expr(self, module, expr):
        word = ''
        op_info = {}
        parse_index = 0
        in_keyword = False
        in_parse_keyword = []
        new_expr = ''
        while parse_index < len(expr):
            if expr[parse_index] == ' ':
                parse_index += 1
                continue
            elif expr[parse_index] == '(':
                if word in OPERATION:
                    op_info = Op_map[word]
                    in_keyword = True
                    in_parse_keyword.append({"keyword": word, "in_parse": in_keyword, 'op_info': op_info})
                    word = ''
                else:
                    word += expr[parse_index]
            elif expr[parse_index] == ',':
                int_search = get_width_value.search(word)
                if int_search:
                    word = f"BitVecVal({int_search.group(2)}, {int_search.group(1)})"
                if in_parse_keyword[-1].get('oprand0') and in_parse_keyword[-1].get('oprand0') != '':
                    in_parse_keyword[-1]['oprand1'] = word.replace('.', '__')
                    word = ''
                else:
                    in_parse_keyword[-1]['oprand0'] = word.replace('.', '__')
                    word = ''
            elif expr[parse_index] == ')':
                is_int = int_word.search(word)
                int_search = get_width_value.search(word)
                if int_search:
                    word = f"BitVecVal({int_search.group(2)}, {int_search.group(1)})"
                if is_int:
                    word += expr[parse_index]
                else:
                    if in_parse_keyword[-1].get('oprand0') and in_parse_keyword[-1].get('oprand0') != '':
                        if in_parse_keyword[-1].get('oprand1') and in_parse_keyword[-1].get('oprand1') != '':
                            in_parse_keyword[-1]['oprand2'] = word.replace('.', '__')
                            # print(f") {in_parse_keyword[-1]['op_info']['op']} {in_parse_keyword[-1]['op_info']['oprands']} oprand2 assyn {word}")
                        else:
                            in_parse_keyword[-1]['oprand1'] = word.replace('.', '__')
                            # print(f") {in_parse_keyword[-1]['op_info']['op']} {in_parse_keyword[-1]['op_info']['oprands']} oprand1 assyn {word}")
                    else:
                        in_parse_keyword[-1]['oprand0'] = word.replace('.', '__')
                        # print(f") {in_parse_keyword[-1]['op_info']['op']} {in_parse_keyword[-1]['op_info']['oprands']} oprand0 assyn {word}")
                    word = ''
                    
                    if in_parse_keyword[-1]['op_info']['oprands'] == 1 and in_parse_keyword[-1].get('oprand0') and in_parse_keyword[-1]['oprand0'] != '':
                        tmp = self.gen_tmp()
                        ty = self.get_width_type(module, in_parse_keyword[-1]['oprand0'])[1]
                        width = self.get_width_type(module, in_parse_keyword[-1]['oprand0'])[0]
                        if ty == 'bool':
                            in_parse_keyword[-1]['oprand0'] = self.bool2bv(in_parse_keyword[-1]['oprand0'])
                            ty = 'UInt'

                        if in_parse_keyword[-1]['keyword'] == "andr" or in_parse_keyword[-1]['keyword'] == "orr":    
                            self.tmpsig[tmp] = ('1', 'UInt')
                            word = in_parse_keyword[-1]['op_info']['op'] + f"({in_parse_keyword[-1]['oprand0']})"
                            
                        elif in_parse_keyword[-1]['keyword'] == "xorr":
                            # Width need to add
                            self.tmpsig[tmp] = ('1', 'UInt')
                            word = self.redxor(f"{in_parse_keyword[-1]['oprand0']}", int(width))

                        else:
                            word = '(' + in_parse_keyword[-1]['op_info']['op'] + in_parse_keyword[-1]['oprand0'] + ')'
                            if in_parse_keyword[-1]['keyword'] == 'assint':
                                self.tmpsig[tmp] = (width, 'SInt') 
                            elif in_parse_keyword[-1]['keyword'] == 'asuint':
                                self.tmpsig[tmp] = (width, 'UInt') 
                            else:
                                self.tmpsig[tmp] = (width, ty)

                        self.z3expr.append(f"{tmp} = {word}")
                        word = tmp
                        in_parse_keyword.pop()
                        if len(in_parse_keyword) == 0:
                            parse_index += 1
                            continue
                        print(f"pop {word}")
                        print(f"expr is: {self.z3expr[-1]}")
                    else:
                        Exception(f"{in_parse_keyword[-1]['keyword']} Oprand not be assigned!")
                    if in_parse_keyword[-1]['op_info']['oprands'] == 2 and in_parse_keyword[-1].get('oprand0') and in_parse_keyword[-1]['oprand0'] != '' and in_parse_keyword[-1].get('oprand1') and in_parse_keyword[-1]['oprand1'] != '':
                        tmp = self.gen_tmp()

                        if in_parse_keyword[-1]['keyword'] == "cat":
                            word = in_parse_keyword[-1]['op_info']['op'] + f"({in_parse_keyword[-1]['oprand0']}, {in_parse_keyword[-1]['oprand1']})"
                            o0_width = self.get_width_type(module, in_parse_keyword[-1]['oprand0'])[0]
                            o1_width = self.get_width_type(module, in_parse_keyword[-1]['oprand1'])[0]
                            width = int(o0_width) + int(o1_width)
                            self.tmpsig[tmp] = (str(width), 'UInt')

                        elif in_parse_keyword[-1]['keyword'] == "pad":
                            # Need to know the type of the oprand. UInt will zero extend, SInt will sign extend.
                            o0_width, o0_ty = self.get_width_type(module, in_parse_keyword[-1]['oprand0'])
                            n = int(in_parse_keyword[-1]['oprand1']) - int(o0_width)
                            if o0_ty == 'UInt':
                                word = in_parse_keyword[-1]['op_info']['op'] + f"({str(n)}, {in_parse_keyword[-1]['oprand0']})"
                            if o0_ty == 'SInt':
                                word = f"If(Extract({int(o0_width) - 1}, {int(o0_width) - 1}, {in_parse_keyword[-1]['oprand0']}) == 1, SignExt({str(n)}, {in_parse_keyword[-1]['oprand0']}), ZeroExt({str(n)}, {in_parse_keyword[-1]['oprand0']}))"

                            self.tmpsig[tmp] = (in_parse_keyword[-1]['oprand1'], o0_ty)
                        elif in_parse_keyword[-1]['keyword'] == "tail":
                            # Need to known the width of oprand0
                            width = self.get_width_type(module, in_parse_keyword[-1]['oprand0'])[0]
                            word = in_parse_keyword[-1]['op_info']['op'] + f"({int(width) -1 - int(in_parse_keyword[-1]['oprand1'])}, 0, {in_parse_keyword[-1]['oprand0']})"
                            self.tmpsig[tmp] = (str(int(width) - int(in_parse_keyword[-1]['oprand1'])), 'UInt')

                        elif in_parse_keyword[-1]['keyword'] == "shr":

                            o0_width, o0_ty = self.get_width_type(module, in_parse_keyword[-1]['oprand0'])
                            o0_width = int(o0_width) - int(in_parse_keyword[-1]['oprand1'])
                            word =  f"Extract({o0_width - 1}, 0, {in_parse_keyword[-1]['op_info']['op']}({in_parse_keyword[-1]['oprand0']},{in_parse_keyword[-1]['oprand1']}))"
                            self.tmpsig[tmp] = (str(o0_width), o0_ty)

                        elif in_parse_keyword[-1]['keyword'] == "shl":
                            o0_width, o0_ty = self.get_width_type(module, in_parse_keyword[-1]['oprand0'])
                            o0_width = int(o0_width) + int(in_parse_keyword[-1]['oprand1'])
                            word = f"{in_parse_keyword[-1]['op_info']['op']}({in_parse_keyword[-1]['oprand0']},BitVecVal(0, {in_parse_keyword[-1]['oprand1']}))"
                            self.tmpsig[tmp] = (o0_width, o0_ty)

                        elif in_parse_keyword[-1]['keyword'] in LOGICOP:
                            word = '(' + in_parse_keyword[-1]['oprand0'] + in_parse_keyword[-1]['op_info']['op'] + in_parse_keyword[-1]['oprand1'] + ')'
                            # Most use in mux, for other ops, leave it to tranform bool to UInt.
                            self.tmpsig[tmp] = ('1', 'bool')
                        else:
                            o0_width, o0_ty = self.get_width_type(module, in_parse_keyword[-1]['oprand0'])
                            if not (o0_width and o0_ty):
                                print(f"[Error]")
                            
                            o1_width, o1_ty = self.get_width_type(module, in_parse_keyword[-1]['oprand1'])
                            if not (o1_width and o1_ty):
                                print(f"[Error]")
                            
                            if (o0_width != o1_width): 
                                (o0_width, o0_ty, in_parse_keyword[-1]['oprand0']), (o0_width, o0_ty, in_parse_keyword[-1]['oprand1']) = self.align_width((o0_width, o0_ty, in_parse_keyword[-1]['oprand0']), (o1_width, o1_ty, in_parse_keyword[-1]['oprand1']))
                                print(f"[Width Mismatch]Op is: ${in_parse_keyword[-1]['keyword']} oprand0 is: {in_parse_keyword[-1]['oprand0']} width: {o0_width}, oprand1 is: {in_parse_keyword[-1]['oprand1']} width: {o1_width}") 

                            if o0_ty == 'bool':
                                in_parse_keyword[-1]['oprand0'] = self.bool2bv(in_parse_keyword[-1]['oprand0'])
                                o0_ty = 'UInt'
                                o0_width = 1
                            if o1_ty == 'bool':
                                in_parse_keyword[-1]['oprand1'] = self.bool2bv(in_parse_keyword[-1]['oprand1'])
                                o1_ty = 'UInt'
                                o1_width = 1

                            if (o0_ty != o1_ty):
                                print(f"[Type Mismatch]Op is: ${in_parse_keyword[-1]['keyword']} oprand0 is: {in_parse_keyword[-1]['oprand0']} type: {o0_ty}, oprand1 is: {in_parse_keyword[-1]['oprand1']} type: {o1_ty}") 
                            
                            width = o0_width
                            ty = o0_ty

                            if in_parse_keyword[-1]['keyword'] in ARITHOP:
                                if in_parse_keyword[-1]['keyword'] == 'add' or in_parse_keyword[-1]['keyword'] == 'sub':            
                                    # Align the oprand width
                                    (o0_width, o0_ty, in_parse_keyword[-1]['oprand0']), (o0_width, o0_ty, in_parse_keyword[-1]['oprand1']) = self.align_width((o0_width, o0_ty, in_parse_keyword[-1]['oprand0']), (o1_width, o1_ty, in_parse_keyword[-1]['oprand1']))
                                    width = int(o1_width) + 1
                                    o0_s = True if o0_ty == 'SInt' else False
                                    o1_s =  True if o1_ty == 'SInt' else False
                                    ty = 'SInt' if o0_s or o1_s else 'UInt'
                                    word = f"Int2BV(BV2Int({in_parse_keyword[-1]['oprand0']}, {o0_s}){in_parse_keyword[-1]['op_info']['op']}BV2Int({in_parse_keyword[-1]['oprand1']}, {o1_s}), {width})"
                                else:
                                    word = '(' + in_parse_keyword[-1]['oprand0'] + in_parse_keyword[-1]['op_info']['op'] + in_parse_keyword[-1]['oprand1'] + ')'
                            else:
                                word = '(' + in_parse_keyword[-1]['oprand0'] + in_parse_keyword[-1]['op_info']['op'] + in_parse_keyword[-1]['oprand1'] + ')'
                            self.tmpsig[tmp] = (str(width), ty)
                        self.z3expr.append(f"{tmp} = {word}")
                        word = tmp
                        in_parse_keyword.pop()
                        if len(in_parse_keyword) == 0:
                            parse_index += 1
                            continue
                        print(f"pop {word}")
                        print(f"expr is: {self.z3expr[-1]}")

                    else:
                        Exception(f"{in_parse_keyword[-1]['keyword']} Oprand not be assigned!")
                    if in_parse_keyword[-1]['op_info']['oprands'] == 3 and in_parse_keyword[-1].get('oprand0') and in_parse_keyword[-1]['oprand0'] != '' and in_parse_keyword[-1].get('oprand1') and in_parse_keyword[-1]['oprand1'] != '' and in_parse_keyword[-1].get('oprand2') and in_parse_keyword[-1]['oprand2'] != '':
                        tmp = self.gen_tmp()
                        if in_parse_keyword[-1]['keyword'] == 'mux':
                            o0_width = self.get_width_type(module, in_parse_keyword[-1]['oprand0'])[0]
                            o0_ty = self.get_width_type(module, in_parse_keyword[-1]['oprand0'])[1]
                            if not (o0_width and o0_ty):
                                print(f"[Error]")

                            o1_width = self.get_width_type(module, in_parse_keyword[-1]['oprand1'])[0]
                            o1_ty = self.get_width_type(module, in_parse_keyword[-1]['oprand1'])[1]

                            if not (o1_width and o1_ty):
                                print(f"[Error]")

                            o2_width = self.get_width_type(module, in_parse_keyword[-1]['oprand2'])[0]
                            o2_ty = self.get_width_type(module, in_parse_keyword[-1]['oprand2'])[1]

                            if not (o0_width and o0_ty):
                                print(f"[Error]")

                            if not o0_width == '1':
                                print(f"[Mux oprand0 width not 1]{in_parse_keyword[-1]['oprand0']} width: {o0_width}")
                            if not o0_ty == 'bool':
                                in_parse_keyword[-1]['oprand0'] = self.bv2bool(in_parse_keyword[-1]['oprand0'])
                                o0_ty = 'bool'
                                o0_width = '1'

                            if (o1_width != o2_width):
                                print(f"[Mux Width Mismatch]Mux oprand1 is: {in_parse_keyword[-1]['oprand1']} width: {o1_width}, oprand2 is: {in_parse_keyword[-1]['oprand2']} width: {o2_width}") 
                                (o1_width, o1_ty, in_parse_keyword[-1]['oprand1']), (o2_width, o2_ty, in_parse_keyword[-1]['oprand2']) = self.align_width((o1_width, o1_ty, in_parse_keyword[-1]['oprand1']), (o2_width, o2_ty, in_parse_keyword[-1]['oprand2']))

                            if (o1_ty != o2_ty):
                                print(f"[Mux Type Mismatch]Mux oprand1 is: {in_parse_keyword[-1]['oprand1']} type: {o1_ty}, oprand2 is: {in_parse_keyword[-1]['oprand2']} type: {o2_ty}") 
                            
                            width = o1_width
                            ty = o1_ty

                            word = in_parse_keyword[-1]["op_info"]['op'] + f"({in_parse_keyword[-1]['oprand0']}, {in_parse_keyword[-1]['oprand1']}, {in_parse_keyword[-1]['oprand2']})"
                        elif in_parse_keyword[-1]['keyword'] == 'bits':
                            o1 = in_parse_keyword[-1]['oprand1']
                            o2 = in_parse_keyword[-1]['oprand2']
                            word = in_parse_keyword[-1]["op_info"]['op'] + f"({in_parse_keyword[-1]['oprand1']}, {in_parse_keyword[-1]['oprand2']}, {in_parse_keyword[-1]['oprand0']})"
                            width = str(int(o1) - int(o2) + 1)
                            ty = 'UInt'       
                        self.tmpsig[tmp] = (width, ty)
                        self.z3expr.append(f"{tmp} = {word}")
                        word = tmp
                        in_parse_keyword.pop()
                        if len(in_parse_keyword) == 0:
                            parse_index += 1
                            continue
                        print(f"pop {word}")
                        print(f"expr is: {self.z3expr[-1]}")

                    else:
                        Exception(f"{in_parse_keyword[-1]['keyword']} Oprand not be assigned!")

            else:
                word += expr[parse_index]

            parse_index += 1
        if len(in_parse_keyword) == 0:
            new_expr = word
            print(f"[SUCCESS]Transform finish!, new_expr is: {new_expr}")
            return new_expr
        else:
            print(f"[FAILD]Transform Faild!, not parse keyword num is: {len(in_parse_keyword)}")
    
    def z3_sigdef(self, module):
        sigdef = list()
        for sig, width in self.pre.signal_width[module].items():
            sigdef.append(f"{sig.replace('.', '__')} = BitVec('{sig.replace('.', '__')}', {width[0]})")
        self.sigdef[module] = sigdef

    def dump_z3py(self, z3pyfile, module):
        with open(z3pyfile, 'w') as f:
            f.write(z3head)
            f.write('\n'.join(self.sigdef[module]))  
            f.write('\n\n')
            expr = f"testio = {self.parse_expr(module, problem)}"
            f.write('\n'.join(self.z3expr))
            f.write('\n\n')
            f.write(expr)
            f.write(z3constraint)
            f.write('\n'.join(self.custom_constraint))
            f.write(z3tail)

    
pre = Preprocess("../specdoctor-logs")
pre.extract_log()
z3s = Z3Solver(pre)
z3s.z3_sigdef('LSU')
z3s.dump_z3py('test.py', 'LSU')



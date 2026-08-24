import re

# --- OLD file ---
with open('data/newOutputData/Cabinet1.txt', 'r') as f:
    old = f.read()

# --- NEW file ---
with open('data/newOutputData2/Cabinet1.txt', 'r') as f:
    new = f.read()

# Helper: parse blocks
def parse_blocks(text):
    blocks = re.split(r'^block \d+$', text, flags=re.MULTILINE)[1:]  # skip preamble
    result = []
    for i, b in enumerate(blocks, 1):
        ids = re.findall(r'^  - id=(\d+)', b, re.MULTILINE)
        score1 = re.search(r'score1=([-\d.]+)', b)
        score2 = re.search(r'score2=([-\d.]+)', b)
        score3 = re.search(r'score3=([-\d.]+)', b)
        block_id = re.search(r'^id=(\d+)', b, re.MULTILINE)
        has_28239641059 = '28239641059' in b
        result.append({
            'block_num': i,
            'block_id': block_id.group(1) if block_id else None,
            'members': ids,
            'member_count': len(ids),
            'score1': score1.group(1) if score1 else None,
            'score2': score2.group(1) if score2 else None,
            'score3': score3.group(1) if score3 else None,
            'has_28239641059': has_28239641059,
        })
    return result

old_blocks = parse_blocks(old)
new_blocks = parse_blocks(new)

# Multi-item blocks
old_multi = [b for b in old_blocks if b['member_count'] > 1]
new_multi = [b for b in new_blocks if b['member_count'] > 1]

print('=== OLD ===')
print(f'Multi-item blocks count: {len(old_multi)}')
print(f'Multi-item block IDs: {[b["block_id"] for b in old_multi]}')
print()

print('=== NEW ===')
print(f'Multi-item blocks count: {len(new_multi)}')
print(f'Multi-item block IDs: {[b["block_id"] for b in new_multi]}')
print()

# Compare block ID lists
old_ids = [b['block_id'] for b in old_multi]
new_ids = [b['block_id'] for b in new_multi]
print(f'Multi-item block ID lists identical: {old_ids == new_ids}')
print()

# Score2 sum
old_score2_sum = sum(float(b['score2']) for b in old_blocks if b['score2'] is not None)
new_score2_sum = sum(float(b['score2']) for b in new_blocks if b['score2'] is not None)
print(f'OLD score2 sum: {old_score2_sum}')
print(f'NEW score2 sum: {new_score2_sum}')
print(f'Score2 diff (NEW - OLD): {new_score2_sum - old_score2_sum}')
print()

# Find block containing 28239641059 in NEW
found_new = False
for b in new_blocks:
    if b['has_28239641059']:
        print(f'=== NEW block containing 28239641059 ===')
        print(f'Block #{b["block_num"]}, id={b["block_id"]}')
        print(f'score1={b["score1"]}, score2={b["score2"]}, score3={b["score3"]}')
        print(f'Member count: {b["member_count"]}')
        print(f'Member IDs: {b["members"]}')
        found_new = True
        break
if not found_new:
    print('28239641059 NOT FOUND in NEW file')

# Also check OLD
for b in old_blocks:
    if b['has_28239641059']:
        print()
        print(f'=== OLD block containing 28239641059 ===')
        print(f'Block #{b["block_num"]}, id={b["block_id"]}')
        print(f'score1={b["score1"]}, score2={b["score2"]}, score3={b["score3"]}')
        break
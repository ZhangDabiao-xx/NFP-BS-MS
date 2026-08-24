import re

def analyze(path, label):
    with open(path, 'r', encoding='utf-8') as f:
        content = f.read()
    
    # Count blocks - find all "block N" headers
    block_count = len(re.findall(r'^block \d+$', content, re.MULTILINE))
    
    # Count blocks with NOTCH_FILL - split by block boundaries
    block_sections = re.split(r'(?=^block \d+$)', content, flags=re.MULTILINE)
    notch_blocks = sum(1 for s in block_sections if 'NOTCH_FILL' in s)
    
    # Total score2 sum
    score2_vals = re.findall(r'score2=([-\d.eE+]+)', content)
    score2_sum = sum(float(v) for v in score2_vals)
    
    # Multi-item blocks: count items per block using "items=" section
    multi_count = 0
    multi_details = []
    for sec in block_sections:
        # Count items by looking for "  - id=" within the items section
        items = re.findall(r'^\s+- id=', sec, re.MULTILINE)
        if len(items) >= 2:
            multi_count += 1
            # Get the block number
            blk_match = re.search(r'^block (\d+)$', sec, re.MULTILINE)
            blk_num = blk_match.group(1) if blk_match else '?'
            # Get item ids
            item_ids = re.findall(r'^\s+- id=(\d+)', sec, re.MULTILINE)
            multi_details.append((blk_num, item_ids))
    
    print(f'=== {label} ===')
    print(f'  blockCount: {block_count}')
    print(f'  NOTCH_FILL blocks: {notch_blocks}')
    print(f'  score2 count: {len(score2_vals)}')
    print(f'  score2 sum: {score2_sum:.6f}')
    print(f'  Multi-item blocks: {multi_count}')
    return multi_details

old = analyze(r'e:\code\NFPKSum\data\newOutputData\Cabinet3.txt', 'OLD')
v2 = analyze(r'e:\code\NFPKSum\data\newOutputData2\Cabinet3.txt', 'V2')
new = analyze(r'e:\code\NFPKSum\data\newOutputData3\Cabinet3.txt', 'NEW')

print()
print('=== Multi-item pairing comparison (OLD vs NEW) ===')
if len(old) != len(new):
    print(f'OLD has {len(old)} multi-item blocks, NEW has {len(new)} multi-item blocks - COUNTS DIFFER.')
    print(f'Showing up to 10 examples:')
    for i, (blk, ids) in enumerate(old[:10]):
        print(f'  OLD block #{blk}: {ids}')
    print('---')
    for i, (blk, ids) in enumerate(new[:10]):
        print(f'  NEW block #{blk}: {ids}')
else:
    diffs = []
    for i in range(len(old)):
        if old[i][1] != new[i][1]:
            diffs.append((i, old[i], new[i]))
    if not diffs:
        print('All multi-item pairings are IDENTICAL between OLD and NEW.')
    else:
        print(f'{len(diffs)} of {len(old)} multi-item blocks have CHANGED pairings:')
        for idx, (ob, nb) in diffs[:10]:
            print(f'  #{idx}: OLD block {ob[0]} ids={ob[1]} -> NEW block {nb[0]} ids={nb[1]}')
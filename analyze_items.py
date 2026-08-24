import json
import sys

with open(r'e:\code\NFPKSum\data\inputData\Cabinet1.json', 'r') as f:
    data = json.load(f)

items = data['items']

def polygon_area_abs(points):
    area = 0.0
    n = len(points)
    for i in range(n):
        j = (i + 1) % n
        area += points[i][0] * points[j][1]
        area -= points[j][0] * points[i][1]
    return abs(area / 2.0)

def bounding_box_area(points):
    xs = [p[0] for p in points]
    ys = [p[1] for p in points]
    return max(0.0, max(xs) - min(xs)) * max(0.0, max(ys) - min(ys))

results = []
for item in items:
    area = polygon_area_abs(item['points'])
    box_area = bounding_box_area(item['points'])
    area_rate = area / box_area if box_area > 0 else 0
    results.append({
        'id': item['id'],
        'smallItem': item.get('smallItem', False),
        'area': area,
        'boxArea': box_area,
        'areaRate': area_rate
    })

# 1. Total items
total = len(results)
print(f'1. Total items: {total}')

# 2. Irregular: smallItem=false AND areaRate < 0.90
irregular = [r for r in results if not r['smallItem'] and r['areaRate'] < 0.90]
print(f'2. Irregular items (smallItem=false AND areaRate < 0.90): {len(irregular)}')
for r in irregular:
    print(f'   id={r["id"]}, area={r["area"]:.2f}, boxArea={r["boxArea"]:.2f}, areaRate={r["areaRate"]:.6f}')

# 3. Range of areaRate
rates = [r['areaRate'] for r in results]
print(f'3. areaRate range: min={min(rates):.6f}, max={max(rates):.6f}')

# 4. Counts below thresholds
below_085 = sum(1 for r in results if r['areaRate'] < 0.85)
below_080 = sum(1 for r in results if r['areaRate'] < 0.80)
below_070 = sum(1 for r in results if r['areaRate'] < 0.70)
print(f'4. areaRate < 0.85: {below_085}, < 0.80: {below_080}, < 0.70: {below_070}')

# 5. Large items (area > 500000) with low areaRate
large_low = [r for r in results if r['area'] > 500000 and r['areaRate'] < 0.90]
print(f'5. Large items (area > 500,000) with areaRate < 0.90: {len(large_low)}')
for r in large_low:
    print(f'   id={r["id"]}, area={r["area"]:.2f}, boxArea={r["boxArea"]:.2f}, areaRate={r["areaRate"]:.6f}')
large_all = [r for r in results if r['area'] > 500000]
print(f'   (All items with area > 500,000: {len(large_all)})')

# 6. Top 5 by area
sorted_by_area = sorted(results, key=lambda r: r['area'], reverse=True)
print(f'6. Top 5 largest items by area:')
for i, r in enumerate(sorted_by_area[:5]):
    print(f'   #{i+1}: id={r["id"]}, area={r["area"]:.2f}, boxArea={r["boxArea"]:.2f}, areaRate={r["areaRate"]:.6f}')
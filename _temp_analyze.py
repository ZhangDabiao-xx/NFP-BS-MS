import json

with open(r"e:\code\NFPKSum\data\inputData\Cabinet1.json", "r", encoding="utf-8") as f:
    data = json.load(f)

# Explore structure
if isinstance(data, dict):
    print("Top-level keys:", list(data.keys()))
    for k, v in data.items():
        if isinstance(v, list):
            print(f"  {k}: list of {len(v)} items")
            if len(v) > 0:
                first = v[0]
                if isinstance(first, dict):
                    print(f"    first item keys: {list(first.keys())}")
                else:
                    print(f"    first item type: {type(first).__name__}")
        elif isinstance(v, dict):
            print(f"  {k}: dict with keys {list(v.keys())}")
            for k2, v2 in v.items():
                if isinstance(v2, list):
                    print(f"    {k2}: list of {len(v2)} items")
                    if len(v2) > 0:
                        first = v2[0]
                        if isinstance(first, dict):
                            print(f"      first item keys: {list(first.keys())}")
elif isinstance(data, list):
    print(f"Root is a list of {len(data)} items")
    if len(data) > 0:
        first = data[0]
        if isinstance(first, dict):
            print(f"  first item keys: {list(first.keys())}")
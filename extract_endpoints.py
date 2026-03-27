import re, os

controllers = []
for root, dirs, files in os.walk('src/main/java'):
    for f in files:
        if f.endswith('Controller.java'):
            controllers.append(os.path.join(root, f))

results = []
for cpath in sorted(controllers):
    with open(cpath, 'r', encoding='utf-8') as fh:
        content = fh.read()
    
    class_match = re.search(r'@RequestMapping\("([^"]+)"\)', content)
    base = class_match.group(1) if class_match else ''
    
    cname = os.path.basename(cpath).replace('.java','')
    
    for m in re.finditer(r'@(Get|Post|Put|Delete|Patch)Mapping(?:\("([^"]*)"[^)]*\)|\(\)|\b)', content):
        method = m.group(1).upper()
        path = m.group(2) if m.group(2) else ''
        full = base + path
        results.append(f'{method} {full} - {cname}')

for r in sorted(set(results)):
    print(r)

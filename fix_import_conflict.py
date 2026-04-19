import re

with open('./src/main/java/io/github/dsheirer/gui/preference/application/ApplicationPreferenceEditor.java', 'r') as f:
    lines = f.readlines()

new_lines = []
skip = False
for line in lines:
    if line.startswith("<<<<<<< HEAD"):
        skip = True
        continue
    if line.startswith("======="):
        skip = False
        continue
    if line.startswith(">>>>>>> origin/master"):
        continue
    if not skip:
        new_lines.append(line)

with open('./src/main/java/io/github/dsheirer/gui/preference/application/ApplicationPreferenceEditor.java', 'w') as f:
    f.writelines(new_lines)

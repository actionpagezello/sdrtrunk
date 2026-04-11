import re

with open('./src/main/java/io/github/dsheirer/gui/SDRTrunk.java', 'r') as f:
    content = f.read()

content = content.replace("viewMenu.getItems().add(new BroadcastStatusVisibleMenuItem());", "")
content = re.sub(r'public class BroadcastStatusVisibleMenuItem\s*{.*?}', '', content, flags=re.DOTALL)

with open('./src/main/java/io/github/dsheirer/gui/SDRTrunk.java', 'w') as f:
    f.write(content)

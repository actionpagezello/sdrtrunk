import re

with open('src/main/java/io/github/dsheirer/audio/broadcast/BroadcastFactory.java', 'r') as f:
    content = f.read()

get_broadcaster_pattern = r'(case ZELLO:\s+return new ZelloConsumerBroadcaster\(\(ZelloConsumerConfiguration\) configuration,\s+inputAudioFormat, mp3Setting, aliasModel\);)'
content = re.sub(get_broadcaster_pattern, r'\1\n                case IAMRESPONDING:\n                    return new IAmRespondingBroadcaster((IAmRespondingConfiguration) configuration, aliasModel);', content)

get_configuration_pattern = r'(case ZELLO:\s+return new ZelloConsumerConfiguration\(format\);)'
content = re.sub(get_configuration_pattern, r'\1\n            case IAMRESPONDING:\n                return new IAmRespondingConfiguration();', content)

with open('src/main/java/io/github/dsheirer/audio/broadcast/BroadcastFactory.java', 'w') as f:
    f.write(content)

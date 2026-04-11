import re

with open('./src/main/java/io/github/dsheirer/gui/SDRTrunk.java', 'r') as f:
    content = f.read()

# Replace LookAndFeelFactory.installJideExtension() logic
old_logic = """        if(operatingSystem.contains("mac") || operatingSystem.contains("nux"))
        {
            try
            {
                UIManager.setLookAndFeel(MetalLookAndFeel.class.getName());
                LookAndFeelFactory.installJideExtension();
            }
            catch(Exception e)
            {
                mLog.error("Error trying to set Metal look and feel for OS [" + operatingSystem + "]");
            }
        }
        else if(operatingSystem.contains("win"))
        {
            try
            {
                LookAndFeelFactory.installJideExtension();
            }
            catch(Exception e)
            {
                mLog.error("Error trying to install JIDE extension for OS [" + operatingSystem + "]");
            }
        }"""

new_logic = """        try {
            com.formdev.flatlaf.FlatLightLaf.setup();
            UIManager.put("Button.arc", 8);
            UIManager.put("Component.arc", 8);
            UIManager.put("ProgressBar.arc", 8);
            UIManager.put("TextComponent.arc", 8);
            LookAndFeelFactory.installJideExtension();
        } catch (Exception e) {
            mLog.error("Failed to setup FlatLaf");
        }"""

content = content.replace(old_logic, new_logic)

with open('./src/main/java/io/github/dsheirer/gui/SDRTrunk.java', 'w') as f:
    f.write(content)

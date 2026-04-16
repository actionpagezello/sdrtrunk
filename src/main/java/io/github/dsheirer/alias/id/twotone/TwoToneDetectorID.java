package io.github.dsheirer.alias.id.twotone;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import io.github.dsheirer.alias.id.AliasID;
import io.github.dsheirer.alias.id.AliasIDType;

public class TwoToneDetectorID extends AliasID implements Comparable<TwoToneDetectorID>
{
    private String mDetectorName;

    public TwoToneDetectorID()
    {
    }

    @Override
    public boolean isAudioIdentifier()
    {
        return false;
    }

    @Override
    public int compareTo(TwoToneDetectorID other)
    {
        if(mDetectorName != null && other.getDetectorName() != null)
        {
            return mDetectorName.compareTo(other.getDetectorName());
        }
        else if(mDetectorName != null)
        {
            return -1;
        }
        else
        {
            return 1;
        }
    }

    @Override
    public boolean equals(Object o)
    {
        if(this == o)
        {
            return true;
        }
        if(!(o instanceof TwoToneDetectorID))
        {
            return false;
        }

        TwoToneDetectorID that = (TwoToneDetectorID)o;

        return getDetectorName() != null ? getDetectorName().equals(that.getDetectorName()) : that.getDetectorName() == null;
    }

    @Override
    public int hashCode()
    {
        return getDetectorName() != null ? getDetectorName().hashCode() : 0;
    }

    public TwoToneDetectorID(String detectorName)
    {
        mDetectorName = detectorName;
        updateValueProperty();
    }

    @JacksonXmlProperty(isAttribute = true, localName = "detectorName")
    public String getDetectorName()
    {
        return mDetectorName;
    }

    public void setDetectorName(String detectorName)
    {
        mDetectorName = detectorName;
        updateValueProperty();
    }

    @JacksonXmlProperty(isAttribute = true, localName = "type", namespace = "http://www.w3.org/2001/XMLSchema-instance")
    @Override
    public AliasIDType getType()
    {
        return AliasIDType.TWO_TONE_DETECTOR;
    }

    @Override
    public boolean isValid()
    {
        return mDetectorName != null;
    }

    @Override
    public boolean matches(AliasID id)
    {
        return false;
    }

    @Override
    public String toString()
    {
        return isValid() ? mDetectorName : "(invalid)";
    }
}

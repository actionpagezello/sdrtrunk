/*******************************************************************************
 *     SDR Trunk 
 *     Copyright (C) 2014 Dennis Sheirer
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>
 ******************************************************************************/package io.github.dsheirer.playlist;

import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

/**
 * AP-fork (ap-15.9.26): keeps a playlist usable by both this fork and upstream SDRTrunk, in either direction.
 *
 * Upstream rejects a whole playlist that contains an element type it does not know, and then saves an empty one over
 * it (Somerville 2026-10-22: the release and the nightly both wiped the fork playlist on its Zello streams).  So the fork
 * writes two files:
 *
 * <ul>
 *   <li>the playlist (default.xml) - everything except the element types only this fork has (Zello, Zello Consumer and
 *       ThinLine streams; CTCSS and NAC alias identifiers).  Upstream can open it.  Fork-only settings stay in it as
 *       extra attributes, which upstream ignores.</li>
 *   <li>the companion (default.ap.xml) - the complete playlist exactly as the fork holds it.  Upstream never reads it.</li>
 * </ul>
 *
 * On load the fork reads the playlist and takes from the companion only what the playlist is missing:
 *
 * <ul>
 *   <li>always: the fork-only streams and alias identifiers, matched by stream name and by alias list + name.</li>
 *   <li>when another program saved the playlist last (it has no fork save stamp): the fork-only settings that program
 *       dropped, filled into the matching channels and aliases.  Anything the other program did write - including any
 *       change made there - is kept.</li>
 * </ul>
 *
 * Channels and aliases are never added or removed from the companion, so deleting one in upstream sticks.
 */
public class PlaylistCompatibility
{
    /** Stream (broadcast configuration) types that only this fork can read */
    public static final Set<String> FORK_ONLY_STREAM_TYPES = Set.of("zelloConfiguration", "zelloConsumerConfiguration",
        "ThinLineRadioConfiguration");

    /** Alias identifier types that only this fork can read */
    public static final Set<String> FORK_ONLY_ALIAS_ID_TYPES = Set.of("ctcss", "nac");

    /** Root attributes written by the fork on save and removed again before the playlist is read */
    /**
     * Settings both programs know that are left out of the file when not in use, so they may appear nowhere in a
     * playlist the other program saved.  Missing means the user turned them off there; they are never refilled.
     */
    private static final Set<String> OPTIONAL_SHARED_SETTINGS = Set.of("event_log_configuration[].logger",
        "record_configuration[].recorder", "source_configuration[sourceConfigTuner].preferred_tuner",
        "source_configuration[sourceConfigTunerMultipleFrequency].preferred_tuner");

    public static final String ATTRIBUTE_FORMAT = "apFormat";
    public static final String ATTRIBUTE_SAVE_ID = "apSaveId";
    public static final String FORMAT_VERSION = "1";

    private PlaylistCompatibility()
    {
    }

    /**
     * Companion file path for a playlist path: default.xml -> default.ap.xml
     */
    public static java.nio.file.Path getCompanion(java.nio.file.Path playlist)
    {
        String name = playlist.getFileName().toString();
        String base = name.toLowerCase().endsWith(".xml") ? name.substring(0, name.length() - 4) : name;
        return playlist.toAbsolutePath().resolveSibling(base + ".ap.xml");
    }

    /**
     * Result of preparing a save: the playlist for upstream and the complete companion.
     */
    public record SaveFiles(String playlist, String companion) {}

    /**
     * Splits the complete playlist as serialized by the fork into the upstream-readable playlist and the companion.
     *
     * @param complete XML of the complete playlist
     * @param saveId stamp written into both, so a later load can tell whether the fork saved the playlist last
     */
    public static SaveFiles prepareSave(String complete, String saveId) throws Exception
    {
        Document companion = parse(complete);
        stamp(companion, saveId);

        Document playlist = parse(complete);
        stamp(playlist, saveId);
        Element root = playlist.getDocumentElement();

        for(Element stream : children(root, "stream"))
        {
            if(FORK_ONLY_STREAM_TYPES.contains(stream.getAttribute("type")))
            {
                root.removeChild(stream);
            }
        }

        for(Element alias : children(root, "alias"))
        {
            for(Element id : children(alias, "id"))
            {
                if(FORK_ONLY_ALIAS_ID_TYPES.contains(id.getAttribute("type")))
                {
                    alias.removeChild(id);
                }
            }
        }

        return new SaveFiles(write(playlist), write(companion));
    }

    /**
     * Result of preparing a load
     *
     * @param xml playlist to read, with the companion merged in and the fork stamps removed
     * @param foreignWriter true when another program saved the playlist last
     * @param restoredStreams fork-only streams taken from the companion
     * @param restoredAliasIds fork-only alias identifiers taken from the companion
     * @param restoredSettings channels and aliases whose dropped fork-only settings were filled in from the companion
     */
    public record LoadResult(String xml, boolean foreignWriter, int restoredStreams, int restoredAliasIds,
                             int restoredSettings) {}

    /**
     * Merges the companion into the playlist.
     *
     * @param playlistXml as read from the playlist file
     * @param companionXml as read from the companion file, or null when there is none
     */
    public static LoadResult prepareLoad(String playlistXml, String companionXml) throws Exception
    {
        Document playlist = parse(playlistXml);
        Element root = playlist.getDocumentElement();
        boolean foreignWriter = !root.hasAttribute(ATTRIBUTE_SAVE_ID);
        Set<String> known = new java.util.HashSet<>(OPTIONAL_SHARED_SETTINGS);
        collectKnownSettings(root, known);
        int streams = 0;
        int aliasIds = 0;
        int settings = 0;

        if(companionXml != null)
        {
            Element companion = parse(companionXml).getDocumentElement();

            //Fork-only streams the playlist does not have, matched by name
            Set<String> streamNames = new java.util.HashSet<>();

            for(Element stream : children(root, "stream"))
            {
                streamNames.add(value(stream, "name"));
            }

            for(Element stream : children(companion, "stream"))
            {
                if(FORK_ONLY_STREAM_TYPES.contains(stream.getAttribute("type")) &&
                    streamNames.add(value(stream, "name")))
                {
                    root.appendChild(playlist.importNode(stream, true));
                    streams++;
                }
            }

            //Aliases: fork-only identifiers always, dropped settings when another program saved last
            Map<Element,Element> aliasPairs = new HashMap<>();
            Map<String,Element> companionAliases = index(children(companion, "alias"), "list", "name");
            Map<String,Element> companionAliasesById = uniqueIndex(children(companion, "alias"),
                PlaylistCompatibility::aliasIdentity);
            Set<Element> usedAliases = new java.util.HashSet<>();
            Map<String,Integer> seen = new HashMap<>();
            List<Element> unmatchedAliases = new ArrayList<>();

            for(Element alias : children(root, "alias"))
            {
                Element source = companionAliases.get(occurrenceKey(alias, seen, "list", "name"));

                if(source == null)
                {
                    unmatchedAliases.add(alias);
                    continue;
                }

                usedAliases.add(source);
            }

            //Renamed (or moved) in the other program: match on alias list and talkgroup/radio identifiers instead
            for(Element alias : unmatchedAliases)
            {
                Element source = companionAliasesById.get(aliasIdentity(alias));

                if(source != null && !usedAliases.contains(source))
                {
                    usedAliases.add(source);
                    aliasPairs.put(alias, source);
                }
            }

            seen.clear();

            for(Element alias : children(root, "alias"))
            {
                Element source = aliasPairs.containsKey(alias) ? aliasPairs.get(alias) :
                    companionAliases.get(occurrenceKey(alias, seen, "list", "name"));

                if(source == null)
                {
                    continue;
                }

                for(Element id : children(source, "id"))
                {
                    if(FORK_ONLY_ALIAS_ID_TYPES.contains(id.getAttribute("type")) && !hasEqualChild(alias, id))
                    {
                        alias.appendChild(playlist.importNode(id, true));
                        aliasIds++;
                    }
                }

                if(foreignWriter && fillMissing(alias, source, Set.of("id"), known))
                {
                    settings++;
                }
            }

            //Channels: dropped settings when another program saved last
            if(foreignWriter)
            {
                Map<String,Element> companionChannels = index(children(companion, "channel"), "system", "site", "name");
                Map<String,Element> companionChannelsByTuning = uniqueIndex(children(companion, "channel"),
                    PlaylistCompatibility::channelIdentity);
                Set<Element> usedChannels = new java.util.HashSet<>();
                Map<Element,Element> pairs = new java.util.LinkedHashMap<>();
                seen.clear();

                for(Element channel : children(root, "channel"))
                {
                    Element source = companionChannels.get(occurrenceKey(channel, seen, "system", "site", "name"));
                    pairs.put(channel, source);

                    if(source != null)
                    {
                        usedChannels.add(source);
                    }
                }

                //Renamed in the other program: match on decoder type and frequency(ies) instead, when that is unique
                for(Map.Entry<Element,Element> pair : pairs.entrySet())
                {
                    if(pair.getValue() == null)
                    {
                        Element source = companionChannelsByTuning.get(channelIdentity(pair.getKey()));

                        if(source != null && !usedChannels.contains(source))
                        {
                            usedChannels.add(source);
                            pair.setValue(source);
                        }
                    }
                }

                for(Map.Entry<Element,Element> pair : pairs.entrySet())
                {
                    if(pair.getValue() != null && fillMissing(pair.getKey(), pair.getValue(), Set.of(), known))
                    {
                        settings++;
                    }
                }
            }
        }

        root.removeAttribute(ATTRIBUTE_FORMAT);
        root.removeAttribute(ATTRIBUTE_SAVE_ID);
        groupSiblings(root);
        return new LoadResult(write(playlist), foreignWriter, streams, aliasIds, settings);
    }

    /**
     * Copies into target what it lacks from source and the other program does not know (it wrote that setting nowhere
     * in the playlist): attributes it does not have, and child elements whose name it has no element of at all.  A child element present once in both is merged the same way, if both are the same type.
     *
     * The NBFM tone filter has an upstream equivalent, so it is not refilled when the other program wrote one (its
     * squelchDecoders are the newer edit); when the companion had an enabled tone filter but the playlist now has no
     * squelch decoders, the user removed the tone squelch in the other program, so the filter is restored disabled.
     *
     * @return true if anything was copied
     */
    private static boolean fillMissing(Element target, Element source, Set<String> excludedChildren, Set<String> known)
    {
        if(!target.getAttribute("type").equals(source.getAttribute("type")))
        {
            return false;
        }

        boolean changed = false;
        Set<String> skip = new java.util.HashSet<>(excludedChildren);
        boolean disableToneFilter = false;

        if("decodeConfigNBFM".equals(source.getAttribute("type")) && children(target, "toneFilters").isEmpty())
        {
            if(hasEntries(target, "squelchDecoders"))
            {
                //Unchanged in the other program: restore the fork's filters, which also carry their labels.  Changed
                //there: the squelch decoders are the newer edit and are adopted when the playlist is read.
                if(!"true".equals(value(source, "toneFilterEnabled")) ||
                    !squelchCodes(target).equals(toneFilterCodes(source)))
                {
                    skip.add("toneFilters");
                    skip.add("@toneFilterEnabled");
                }
            }
            else if("true".equals(value(source, "toneFilterEnabled")) && hasEntries(source, "squelchDecoders") &&
                known.contains(settingKey(source, "squelchDecoders")))
            {
                //Only a program that has squelch decoders (it writes them on every NBFM channel) can have removed one;
                //releases before them (0.6.1) drop the tone squelch unknowingly, so the fork's filter is restored
                disableToneFilter = true;
            }
        }

        NamedNodeMap attributes = source.getAttributes();

        for(int x = 0; x < attributes.getLength(); x++)
        {
            Attr attribute = (Attr)attributes.item(x);
            String name = attribute.getName();

            if(!has(target, name) && !skip.contains("@" + name) && !name.startsWith("xmlns") &&
                !known.contains(settingKey(source, name)))
            {
                target.setAttributeNS(attribute.getNamespaceURI(), name, attribute.getValue());
                changed = true;
            }
        }

        if(disableToneFilter)
        {
            for(Element element : children(target, "toneFilterEnabled"))
            {
                target.removeChild(element);
            }

            target.setAttribute("toneFilterEnabled", "false");
        }

        List<String> names = new ArrayList<>();

        for(Element child : children(source, null))
        {
            if(!names.contains(child.getTagName()))
            {
                names.add(child.getTagName());
            }
        }

        for(String name : names)
        {
            if(skip.contains(name))
            {
                continue;
            }

            //The other program writes this setting elsewhere, so it knows it: missing here means the user removed it
            if(!target.hasAttribute(name) && children(target, name).isEmpty() && known.contains(settingKey(source, name)))
            {
                continue;
            }

            List<Element> sourceChildren = children(source, name);
            List<Element> targetChildren = children(target, name);

            if(targetChildren.isEmpty() && !target.hasAttribute(name))
            {
                for(Element child : sourceChildren)
                {
                    target.appendChild(target.getOwnerDocument().importNode(child, true));
                }

                changed = true;
            }
            else if(targetChildren.size() == 1 && sourceChildren.size() == 1)
            {
                changed |= fillMissing(targetChildren.get(0), sourceChildren.get(0), Set.of(), known);
            }
        }

        return changed;
    }

    /**
     * CTCSS/DCS codes of an NBFM configuration's upstream squelch decoders, normalised for comparison: CTCSS as its
     * frequency, DCS as its code name.
     */
    private static Set<String> squelchCodes(Element nbfm)
    {
        Set<String> codes = new java.util.TreeSet<>();

        for(Element wrapper : children(nbfm, "squelchDecoders"))
        {
            for(Element decoder : children(wrapper, null))
            {
                codes.add(normalise(decoder.getAttribute("squelchType"), decoder.getAttribute("value")));
            }
        }

        return codes;
    }

    /**
     * CTCSS/DCS codes of an NBFM configuration's fork tone filters, normalised as squelchCodes().
     */
    private static Set<String> toneFilterCodes(Element nbfm)
    {
        Set<String> codes = new java.util.TreeSet<>();

        for(Element wrapper : children(nbfm, "toneFilters"))
        {
            for(Element filter : children(wrapper, null))
            {
                String type = filter.getAttribute("toneType");

                if("CTCSS".equals(type) || "DCS".equals(type))
                {
                    codes.add(normalise(type, filter.getAttribute("value")));
                }
            }
        }

        return codes;
    }

    private static String normalise(String type, String value)
    {
        value = value == null ? "" : value.trim();

        if("CTCSS".equalsIgnoreCase(type))
        {
            float frequency = -1;
            java.util.regex.Matcher upstreamName = java.util.regex.Pattern.compile("TONE_(\\d{3,4})").matcher(value);

            if(upstreamName.matches())
            {
                frequency = Integer.parseInt(upstreamName.group(1)) / 10.0f;
            }
            else
            {
                try
                {
                    frequency = io.github.dsheirer.module.decode.ctcss.CTCSSCode.valueOf(value).getFrequency();
                }
                catch(IllegalArgumentException notForkName)
                {
                    try
                    {
                        frequency = Float.parseFloat(value);
                    }
                    catch(NumberFormatException notFrequency)
                    {
                        //left at -1
                    }
                }
            }

            return "CTCSS:" + String.format(java.util.Locale.US, "%.1f", frequency);
        }

        return type.toUpperCase() + ":" + value;
    }

    private static boolean hasEntries(Element element, String wrapper)
    {
        for(Element child : children(element, wrapper))
        {
            if(!children(child, null).isEmpty())
            {
                return true;
            }
        }

        return false;
    }

    private static boolean hasEqualChild(Element parent, Element candidate)
    {
        for(Element child : children(parent, candidate.getTagName()))
        {
            if(child.isEqualNode(candidate))
            {
                return true;
            }
        }

        return false;
    }

    /**
     * Makes elements of the same name adjacent, throughout the document, keeping their order.  Lists are read
     * unwrapped: when elements of one name are split into two runs, Jackson keeps only the LAST run and silently
     * drops the rest.  Elements restored from the companion are appended, so without this a playlist whose streams
     * were written before its channels (Jackson's property order varies between runs) loses every stream that was in
     * the playlist file - Roger Baker 2026-10-23 lost its two Rdio Scanner ingest streams this way.
     */
    private static void groupSiblings(Element parent)
    {
        Map<String,List<Element>> groups = new java.util.LinkedHashMap<>();

        for(Element child : children(parent, null))
        {
            groups.computeIfAbsent(child.getTagName(), k -> new ArrayList<>()).add(child);
        }

        boolean split = false;
        String previous = null;
        Set<String> finished = new java.util.HashSet<>();

        for(Element child : children(parent, null))
        {
            if(!child.getTagName().equals(previous))
            {
                if(finished.contains(child.getTagName()))
                {
                    split = true;
                }

                if(previous != null)
                {
                    finished.add(previous);
                }

                previous = child.getTagName();
            }
        }

        if(split)
        {
            for(List<Element> group : groups.values())
            {
                for(Element child : group)
                {
                    parent.removeChild(child);
                    parent.appendChild(child);
                }
            }
        }

        for(Element child : children(parent, null))
        {
            groupSiblings(child);
        }
    }

    /**
     * Records every setting the other program wrote anywhere in the playlist, keyed by the owning element and type.  A
     * setting it wrote somewhere is one it knows, so where it is missing the user removed it and it is not refilled; a
     * setting it wrote nowhere is one it does not know, and only those are refilled from the companion.
     */
    private static void collectKnownSettings(Element element, Set<String> known)
    {
        NamedNodeMap attributes = element.getAttributes();

        for(int x = 0; x < attributes.getLength(); x++)
        {
            known.add(settingKey(element, ((Attr)attributes.item(x)).getName()));
        }

        for(Element child : children(element, null))
        {
            known.add(settingKey(element, child.getTagName()));
            collectKnownSettings(child, known);
        }
    }

    /**
     * Key for a setting: owning element name and type, then the setting's name (attribute and element alike).
     */
    private static String settingKey(Element owner, String name)
    {
        return owner.getTagName() + "[" + owner.getAttribute("type") + "]." + name;
    }

    /**
     * A channel's tuning: decoder type and source frequency(ies).  Used to pair a channel renamed in the other program.
     */
    private static String channelIdentity(Element channel)
    {
        StringBuilder sb = new StringBuilder();

        for(Element decode : children(channel, "decode_configuration"))
        {
            sb.append(decode.getAttribute("type"));
        }

        List<String> frequencies = new ArrayList<>();

        for(Element source : children(channel, "source_configuration"))
        {
            if(source.hasAttribute("frequency"))
            {
                frequencies.add(source.getAttribute("frequency").trim());
            }
            else
            {
                //Single frequency written as an element, or a multi-frequency source's frequency list
                for(Element element : toList(source.getElementsByTagName("frequency")))
                {
                    frequencies.add(element.hasAttribute("value") ? element.getAttribute("value").trim() :
                        element.getTextContent().trim());
                }
            }
        }

        java.util.Collections.sort(frequencies);
        return frequencies.isEmpty() ? null : sb + "|" + frequencies;
    }

    /**
     * An alias's identity apart from its name: alias list plus its talkgroup and radio identifiers.  Used to pair an
     * alias renamed in the other program.
     */
    private static String aliasIdentity(Element alias)
    {
        List<String> ids = new ArrayList<>();

        for(Element id : children(alias, "id"))
        {
            String type = id.getAttribute("type");

            if(type.startsWith("talkgroup") || type.startsWith("radio"))
            {
                ids.add(type + "=" + id.getAttribute("value") + id.getAttribute("min") + "-" + id.getAttribute("max") +
                    "/" + id.getAttribute("protocol"));
            }
        }

        java.util.Collections.sort(ids);
        return ids.isEmpty() ? null : value(alias, "list") + "|" + ids;
    }

    private static List<Element> toList(org.w3c.dom.NodeList list)
    {
        List<Element> elements = new ArrayList<>();

        for(int x = 0; x < list.getLength(); x++)
        {
            if(list.item(x) instanceof Element element)
            {
                elements.add(element);
            }
        }

        return elements;
    }

    /**
     * Index of elements by a computed key, leaving out any key that more than one element shares, so a fallback match
     * is never ambiguous.
     */
    private static Map<String,Element> uniqueIndex(List<Element> elements,
                                                   java.util.function.Function<Element,String> keyFunction)
    {
        Map<String,Element> index = new HashMap<>();
        Set<String> duplicated = new java.util.HashSet<>();

        for(Element element : elements)
        {
            String key = keyFunction.apply(element);

            if(key != null && index.put(key, element) != null)
            {
                duplicated.add(key);
            }
        }

        duplicated.forEach(index::remove);
        return index;
    }

    /**
     * Index of elements by the given attributes, with an occurrence suffix so duplicates pair up in order.
     */
    private static Map<String,Element> index(List<Element> elements, String... attributes)
    {
        Map<String,Element> index = new HashMap<>();
        Map<String,Integer> seen = new HashMap<>();

        for(Element element : elements)
        {
            index.put(occurrenceKey(element, seen, attributes), element);
        }

        return index;
    }

    private static String occurrenceKey(Element element, Map<String,Integer> seen, String... attributes)
    {
        StringBuilder sb = new StringBuilder();

        for(String attribute : attributes)
        {
            sb.append(value(element, attribute)).append('\u0000');
        }

        String key = sb.toString();
        int occurrence = seen.merge(key, 1, Integer::sum);
        return key + occurrence;
    }

    /**
     * True if the element has the property, as an attribute or as a child element.
     */
    private static boolean has(Element element, String name)
    {
        String local = name.contains(":") ? name.substring(name.indexOf(':') + 1) : name;
        return element.hasAttribute(name) || !children(element, name).isEmpty() || !children(element, local).isEmpty();
    }

    /**
     * A property of an element.  Jackson writes a property declared as an attribute as a child element instead when it
     * comes after an element property in its serialization order, and that order is not fixed between runs - upstream
     * and this fork both do it - so a key such as an alias's list name may be either.
     */
    private static String value(Element element, String name)
    {
        if(element.hasAttribute(name))
        {
            return element.getAttribute(name);
        }

        List<Element> children = children(element, name);
        return children.isEmpty() ? "" : children.get(0).getTextContent().trim();
    }

    private static void stamp(Document document, String saveId)
    {
        Element root = document.getDocumentElement();
        root.setAttribute(ATTRIBUTE_FORMAT, FORMAT_VERSION);
        root.setAttribute(ATTRIBUTE_SAVE_ID, saveId);
    }

    /**
     * Direct child elements, optionally only those with the given tag name.
     */
    private static List<Element> children(Element parent, String name)
    {
        List<Element> children = new ArrayList<>();

        for(Node node = parent.getFirstChild(); node != null; node = node.getNextSibling())
        {
            if(node instanceof Element element && (name == null || element.getTagName().equals(name)))
            {
                children.add(element);
            }
        }

        return children;
    }

    private static Document parse(String xml) throws Exception
    {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    private static String write(Document document) throws Exception
    {
        //Drop whitespace-only text left from the parsed indentation so the output indents cleanly
        removeWhitespace(document.getDocumentElement());
        Transformer transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        StringWriter writer = new StringWriter();
        transformer.transform(new DOMSource(document), new StreamResult(writer));
        return TYPE_NOT_FIRST.matcher(writer.toString()).replaceAll("<$1 type=\"$3\"$2");
    }

    /**
     * An element start tag whose type attribute is not its first attribute.  The DOM writes attributes in
     * alphabetical order, but Jackson must see the type id first: when it does not, it buffers the element and then
     * cannot read an unwrapped list inside it - a multi-frequency tuner source's frequency list fails with "Cannot
     * deserialize value of type ArrayList<Long> from String" and the whole playlist is unreadable (Metro-25
     * 2026-10-25, every trunked system with more than one control channel).  Attribute values never contain a quote
     * (the writer escapes it), so the attribute list can be matched safely.
     */
    private static final java.util.regex.Pattern TYPE_NOT_FIRST = java.util.regex.Pattern.compile(
        "<([A-Za-z_][\\w:.-]*)((?:\\s+[\\w:.-]+=\"[^\"]*\")+?)\\s+type=\"([^\"]*)\"");

    private static void removeWhitespace(Node node)
    {
        Node child = node.getFirstChild();

        while(child != null)
        {
            Node next = child.getNextSibling();

            if(child.getNodeType() == Node.TEXT_NODE && child.getTextContent().isBlank())
            {
                node.removeChild(child);
            }
            else if(child.getNodeType() == Node.ELEMENT_NODE)
            {
                removeWhitespace(child);
            }

            child = next;
        }
    }
}

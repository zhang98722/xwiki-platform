/*
 * Copyright 2006, XpertNet SARL, and individual contributors as indicated
 * by the contributors.txt.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 *
 * @author ludovic
 * @author sdumitriu
 */


package com.xpn.xwiki.render.filter;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.radeox.api.engine.RenderEngine;
import org.radeox.api.engine.WikiRenderEngine;
import org.radeox.filter.context.FilterContext;
import org.radeox.filter.interwiki.InterWiki;
import org.radeox.filter.regex.LocaleRegexTokenFilter;
import org.radeox.util.Encoder;
import org.radeox.util.StringBufferWriter;

import java.io.IOException;
import java.io.Writer;


/*
* LinkTestFilter finds [text] in its input and transforms this
* to <a href="text">...</a> if the wiki page exists. If not
* it adds a [create text] to the output.
*
* @author stephan
* @team sonicteam
* @version $Id: XWikiLinkFilter.java 448 2005-01-26 08:37:17Z ldubost $
*/

public class XWikiLinkFilter extends LocaleRegexTokenFilter {

    private static Log log = LogFactory.getLog(XWikiLinkFilter.class);


    /**
     * The regular expression for detecting WikiLinks.
     * Overwrite in subclass to support other link styles like
     * OldAndUglyWikiLinking :-)
     *
     * /[A-Z][a-z]+([A-Z][a-z]+)+/
     * wikiPattern = "\\[(.*?)\\]";
     */

    protected String getLocaleKey() {
        return "filter.link";
    }

    protected void setUp(FilterContext context) {
        context.getRenderContext().setCacheable(true);
    }

    public void handleMatch(StringBuffer buffer, org.radeox.regex.MatchResult result, FilterContext context) {
        RenderEngine engine = context.getRenderContext().getRenderEngine();

        if (engine instanceof WikiRenderEngine) {
            WikiRenderEngine wikiEngine = (WikiRenderEngine) engine;
            Writer writer = new StringBufferWriter(buffer);

            String str = result.group(1);
            if (str != null) {
            	// TODO: This line creates bug XWIKI-188. The encoder seems to be broken. Fix this!
                // trim the name and unescape it
                str = Encoder.unescape(str.trim());
                String text = null, href = null, target = null;
                boolean specificText = false;

                // Is there an alias like [alias|link] ?
                int pipeIndex = str.indexOf('|');
                int pipeLength = 1;
                if (pipeIndex==-1) {
                    pipeIndex = str.indexOf('>');
                }
                if (pipeIndex==-1) {
                    pipeIndex = str.indexOf("&gt;");
                    pipeLength = 4;
                }
                if (-1 != pipeIndex) {
                    text = str.substring(0, pipeIndex).trim();
                    str = str.substring(pipeIndex + pipeLength);
                    specificText = true;
                }

                // Is there a target like [alias|link|target] ?
                pipeIndex = str.indexOf('|');
                pipeLength = 1;
                if (pipeIndex==-1) {
                    pipeIndex = str.indexOf('>');
                }
                if (pipeIndex==-1) {
                    pipeIndex = str.indexOf("&gt;");
                    pipeLength = 4;
                }
                if (-1 != pipeIndex) {
                    target = str.substring(pipeIndex + pipeLength).trim();
                    str = str.substring(0, pipeIndex);
                }
                // Done splitting

                // Fill in missing components
                href = str.trim();
                if (text == null) {
                    text = href;
                }
                // Done, now print the link

                // Determine target type: external, interwiki, internal
                int protocolIndex = href.indexOf("://");
                if (((protocolIndex>=0)&&(protocolIndex<10))
                    ||(href.indexOf("mailto:")==0)) {
                    // External link
                    buffer.append("<span class=\"wikiexternallink\"><a href=\"");
                    buffer.append(href);
                    buffer.append("\"");
                    if(target != null){
                        buffer.append(" target=\"" + target + "\"");
                    }
                    buffer.append(">");
                    buffer.append(Encoder.toEntity(text.charAt(0)) + text.substring(1));
                    buffer.append("</a></span>");
                    return;
                }

                int hashIndex = href.lastIndexOf('#');
                String hash = "";

                if (-1 != hashIndex && hashIndex != href.length() -1) {
                    hash = href.substring(hashIndex + 1);
                    href = href.substring(0, hashIndex);
                }

                /*
                // We need to keep this in XWiki
                int colonIndex = name.indexOf(':');
                // typed link ?
                if (-1 != colonIndex) {
                    // for now throw away the type information
                    name = name.substring(colonIndex + 1);
                }
                */

                int atIndex = href.lastIndexOf('@');
                // InterWiki link
                if (-1 != atIndex) {
                    String extSpace = href.substring(atIndex + 1);
                    // Kown extarnal space?
                    InterWiki interWiki = InterWiki.getInstance();
                    if (interWiki.contains(extSpace)) {
                        href = href.substring(0, atIndex);
                        try {
                            if (-1 != hashIndex) {
                                interWiki.expand(writer, extSpace, href, text, hash);
                            } else {
                                interWiki.expand(writer, extSpace, href, text);
                            }
                        } catch (IOException e) {
                            log.debug("InterWiki " + extSpace + " not found.");
                        }
                    } else {
                        buffer.append("&#91;<span class=\"error\">");
                        buffer.append(result.group(1));
                        buffer.append("?</span>&#93;");
                    }
                } else {
                    // internal link
                    if (wikiEngine.exists(href)) {
                        if(specificText == false) {
                            text = getWikiView(href);
                            wikiEngine.appendLink(buffer, href, text);
                        } else {
                            // Do not add hash if an alias was given
                            wikiEngine.appendLink(buffer, href, text, hash);
                        }
                    } else if (wikiEngine.showCreate()) {
                        if(specificText == false) {
                            text = getWikiView(href);
                        }
                        wikiEngine.appendCreateLink(buffer, href, text);
                        // links with "create" are not cacheable because
                        // a missing wiki could be created
                        context.getRenderContext().setCacheable(false);
                    } else {
                        // cannot display/create wiki, so just display the text
                        buffer.append(text);
                    }
                    if(target != null){
                        int where = buffer.lastIndexOf(" href=\"");
                        if(where >= 0) {
                            buffer.insert(where, " target=\"" + target + "\"");
                        }
                    }
                }
            } else {
                buffer.append(Encoder.escape(result.group(0)));
            }
        }
    }

    /**
     * Returns the view of the wiki name that is shown to the
     * user. Overwrite to support other views for example
     * transform "WikiLinking" to "Wiki Linking".
     * Does nothing by default.
     *
     * @return view The view of the wiki name
     */
    protected String getWikiView(String name) {
        return convertWikiWords(name);
    }

    public static String convertWikiWords(String name) {
        try {
            name = name.substring(name.indexOf(".") + 1);
            return name.replaceAll("([a-z])([A-Z])", "$1 $2");
        } catch (Exception e) {
            return name;
        }
    }

}

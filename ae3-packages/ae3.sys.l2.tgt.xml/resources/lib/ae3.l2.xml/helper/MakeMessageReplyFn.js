/**
 * this must be bint to XmlSkinHelper instance
 */

const FiltersFormLayout = require("./FiltersFormLayout");
const formatXmlAttributes = Format.xmlAttributes;
const formatXmlElement = Format.xmlElement;
const formatXmlElements = Format.xmlElements;
const formatXmlNodeValue = Format.xmlNodeValue;
const formatXmlAttributeFragment = Format.xmlAttributeFragment;

function makeMessageReply(context, layout){
	const query = context?.query;
	if(false && query?.parameters.___output){
		switch(query.parameters.___output){
		case "xml":
			return require("ae3/xml").makeMessageReply(query, layout);
		case "xls":
			return require("ae3/xls").makeMessageReply(query, layout);
		case "txt":
			return require("ae3/txt").makeMessageReply(query, layout);
		case "pdf":
			return require("ae3/pdf").makeMessageReply(query, layout);
		}
	}

	layout = this.internUiMessageEnrich(layout);
	
	const code = layout.code;
	
	const element = layout.rootName || "message";
	
	var message = layout.message;
	var reason = layout.reason;
	
	const title = layout.title || context.title || context.share?.systemName || "Message";
	const detail = layout.detail;
	
	const attributes = "string" === typeof title
		? Object.create(layout.attributes ?? null, {
			title : {
				value : title,
				enumerable : true
			},
			code : {
				value : code,
				enumerable : true
			},
			icon : {
				value : layout.icon,
				enumerable : true
			},
			hl : {
				value : layout.hl,
				enumerable : true
			},
			zoom : {
				value : layout.zoom,
				enumerable : true
			}
		})
		: Object.create(title, {
			code : {
				value : code,
				enumerable : true
			},
			icon : {
				value : layout.icon,
				enumerable : true
			},
			hl : {
				value : layout.hl,
				enumerable : true
			}
		})
	;

	const filters = layout.filters ?? message?.filters ?? context.layoutFilters;
	
	const formatFull = query && query.parameters.format !== "clean" && !layout.clean && context.client?.uiFormat !== "clean";

	var xml = "";
	$output(xml){
		%><<%= element; %><%= formatXmlAttributes(attributes); %> layout="message"><%
		
			if(context.share){
				= formatXmlElement("client", context.share.clientElementProperties(context));
				if(formatFull && context.rawHtmlHeadData){
					%><rawHeadData><%
						%><![CDATA[<%
							= context.rawHtmlHeadData;
						%>]]><%
					%></rawHeadData><%
				}
			}
			
			if(formatFull){
				
				if(layout.prefix){
					= this.internOutputValue("prefix", layout.prefix);
				}
				
				if(filters?.fields){
					= formatXmlElement("prefix", new FiltersFormLayout(filters));
				}
				
			}
			
			%><reason><%= formatXmlNodeValue(reason ? (reason.title || reason) : "Unclassified message.") %></reason><%
			
			if(message && message !== reason){
				if("string" === typeof message){
					%><message debug="x-string" class="code style--block"><%= formatXmlNodeValue(message) %></message><%
				}else //
				if(message.layout){
					= formatXmlElements("message", message);
					// = this.internOutputValue("message", message);
				}else{
					%><message debug="x-non-layout" class="code style--block"><%= formatXmlNodeValue(Format.jsDescribe(message)) %></message><%
				}
			}
			
			if(formatFull){
				
				if(detail){
					if("string" === typeof detail){
						%><detail debug="x-string" class="code style--block"><%= formatXmlNodeValue(detail) %></detail><%
					}else //
					if(detail.layout){
						= formatXmlElements("detail", detail);
						// = this.internOutputValue("detail", detail);
					}else{
						%><detail debug="x-non-layout" class="code style--block"><%= formatXmlNodeValue(Format.jsDescribe(detail)) %></detail><%
					}
				}
				
				if(layout.help || message?.help){
					%><help src="<%= formatXmlAttributeFragment(layout.help || message?.help) %>"/><%
				}
				
			}
			
		%></<%= element; %>><%
	}
	return {
		layout	: "xml",
		code	: code,
		xsl		: "/!/skin/skin-standard-xml/show.xsl",
		content	: xml,
		cache	: message.cache,
		delay	: message.delay
	};
}

module.exports = makeMessageReply;

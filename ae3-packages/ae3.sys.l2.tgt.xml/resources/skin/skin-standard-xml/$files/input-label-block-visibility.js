/**
 * Prevent validation running on invisible blocks
 */


var blockVisibilityUpdate = function(block, event){
	const es = block.querySelectorAll("INPUT[x-ui-input=true], SELECT[x-ui-input=true], TEXTAREA[x-ui-input=true]");
	if(!es){
		console.log("blockVisibility: no eligible inputs, %s", this.name);
		return;
	}
	const ea = "function" === typeof es.forEach ? es : [].concat(es);
	if(block.checkVisibility({visibilityProperty:true,opacityProperty:true})){
		console.log("blockVisibility: check, visible, %s", this.name);
		ea.forEach(function(x){ 
			x.removeAttribute("disabled"); 
		});
	}else{
		console.log("blockVisibility: check, hidden, %s", this.name);
		ea.forEach(function(x){ 
			x.setAttribute("disabled", "disabled");
		});
	}
};

function initInputDisableInvisible(){
	var blocks = document.querySelectorAll("INPUT.el-radio+LABEL.el-radio+.el-radio-sel-item");
	if(!blocks || blocks.length == 0){
		console.log("blockVisibility: no illegible blocks found");
		return;
	}
	if("function" !== typeof blocks[0].checkVisibility){
		console.log("blockVisibility: checkVisibility is not supported, won't initialize.");
		return;
	}
	var i, block, input, fn;
	for(i = blocks.length - 1; i >= 0; --i){
		block = blocks[i];
		input = block.previousElementSibling.previousElementSibling;
		if(!input.form || input.hasAttribute("disabled")) {
			console.log("InputVisibility: skip element: %s", block.name || block);
			continue;
		}
		fn = blockVisibilityUpdate.bind(input, block);
		input.setAttribute("x-ui-debug", "block-visibility");
		block.setAttribute("x-ui-debug", "block-visibility");
		input.addEventListener("change", fn);
		input.addEventListener("input", fn);
		block.addEventListener("transitionend", setTimeout.bind(null, fn, 17));
		setTimeout(fn, 17);
	}
}

/**
 * show.xsl.tpl:181's `<xsl:value-of disable-output-escaping="yes" select="rawHeadData[not($clean)]"/>`
 * injects real production markup (DataTables' library-loading <script src> tags, an inline init
 * <script>, a <style> block - see Ae3WebService.js's prepareHtmlTable()) straight into <head>, with
 * no wrapping element of its own - it's a bare disable-output-escaping value, sibling to <head>'s
 * other (all-element) children.
 *
 * ru.myx.ae3.l2.xml.XslServerRender's DisableEscapingNeutralizingReceiver (server-side Saxon
 * rendering path only - this is a no-op for the original client-side-XSLT consumer, which applies
 * disable-output-escaping directly) now unconditionally CDATA-wraps that entire raw value, so the
 * transformed application/xhtml+xml document stays well-formed XML. A CDATA section's content is
 * delivered to the DOM as inert text - a real XML parser never executes markup found inside one -
 * so this reconstructs and activates the real elements from it.
 *
 * Placed here (not a new file - show.xsl.tpl is off-limits for edits, and this is one of the few
 * skin-standard-xml assets guaranteed loaded on every page it renders, via show.xsl.tpl's own
 * initAll()/require.script() bootstrap) rather than in a file that better matches this logic's own
 * topic - see this package's MAGIC.md for the fuller placement rationale.
 */

/**
 * Splits raw markup text into top-level <script>/<style> blocks. Deliberately not
 * DOMParser/innerHTML-based: this document may be XML (application/xhtml+xml), where innerHTML's
 * fragment-parsing algorithm enforces XML well-formedness and would choke on a bare "<" inside an
 * inline script's own text (e.g. DataTables' real sDom value, `'<"tbar-up"...'`). Scanning for the
 * literal closing-tag token only, ignoring any other "<" in between, matches HTML's own raw-text
 * parsing rule for <script>/<style> and sidesteps that entirely.
 */
function rawHeadDataExtractBlocks(raw){
	var blocks = [];
	var openTagRe = /^<(script|style)\b([^>]*)>/i;
	var i = 0;
	while(i < raw.length){
		var openMatch = openTagRe.exec(raw.slice(i));
		if(!openMatch){
			break;
		}
		var tag = openMatch[1].toLowerCase();
		var contentStart = i + openMatch[0].length;
		var closeToken = "</" + tag;
		var closeIdx = raw.toLowerCase().indexOf(closeToken, contentStart);
		if(closeIdx === -1){
			console.log("rawHeadData: unterminated <%s>, stopping", tag);
			break;
		}
		var gtIdx = raw.indexOf(">", closeIdx);
		if(gtIdx === -1){
			console.log("rawHeadData: malformed closing tag for <%s>, stopping", tag);
			break;
		}
		blocks.push({
			tag		: tag,
			attrsText	: openMatch[2],
			content		: raw.slice(contentStart, closeIdx)
		});
		i = gtIdx + 1;
	}
	return blocks;
}

/** Parses a raw `name="value"`/`name='value'`/`name=value`/`name` attribute-text run. */
function rawHeadDataParseAttrs(attrsText){
	var attrs = [];
	var re = /([^\s=\/]+)\s*(?:=\s*("([^"]*)"|'([^']*)'|[^\s"'>]+))?/g;
	var m;
	while((m = re.exec(attrsText))){
		attrs.push([m[1], m[3] !== undefined ? m[3] : (m[4] !== undefined ? m[4] : (m[2] || ""))]);
	}
	return attrs;
}

/**
 * Builds a real, live element for one extracted block via document.createElement + explicit
 * attribute/text assignment - never innerHTML, which never executes a <script> it inserts.
 */
function rawHeadDataActivateBlock(block){
	var el = document.createElement(block.tag);
	var attrs = rawHeadDataParseAttrs(block.attrsText);
	for(var i = 0; i < attrs.length; i++){
		el.setAttribute(attrs[i][0], attrs[i][1]);
	}
	if(block.tag === "script" && el.hasAttribute("src")){
		// preserve document-order execution: a dynamically-inserted <script src> defaults to
		// async, which would let jquery.dataTables.min.js race ahead of jquery itself.
		el.async = false;
	}
	if(block.content){
		el.textContent = block.content;
	}
	document.head.appendChild(el);
}

/** Entry point: finds rawHeadData's CDATA section(s) among <head>'s children (the only source of
 * CDATA_SECTION_NODE children <head> ever has in this skin - a safe, unambiguous marker, no id/
 * class needed), concatenates them in document order, and activates every block found in the
 * result. A page with no such content (nothing CDATA-wrapped, or a plain-HTML host document where
 * "<![CDATA[" never parses as a real node type) is a safe, silent no-op. */
function initRawHeadDataUnwrap(){
	var head = document.head;
	if(!head){
		return;
	}
	var raw = "";
	var found = false;
	for(var i = 0; i < head.childNodes.length; i++){
		var node = head.childNodes[i];
		if(node.nodeType === Node.CDATA_SECTION_NODE){
			raw += node.data;
			found = true;
		}
	}
	if(!found || !raw){
		return;
	}
	var blocks = rawHeadDataExtractBlocks(raw);
	console.log("rawHeadData: unwrapping %s block(s)", blocks.length);
	for(var j = 0; j < blocks.length; j++){
		rawHeadDataActivateBlock(blocks[j]);
	}
}

initRawHeadDataUnwrap();

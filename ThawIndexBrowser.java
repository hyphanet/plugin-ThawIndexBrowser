package plugins.ThawIndexBrowser;

import java.io.IOException;
import java.net.MalformedURLException;

import nu.xom.Builder;
import nu.xom.Document;
import nu.xom.Element;
import nu.xom.Elements;
import nu.xom.ParsingException;
import nu.xom.ValidityException;
import freenet.client.FetchException;
import freenet.client.FetchResult;
import freenet.client.HighLevelSimpleClient;
import freenet.clients.http.PageMaker;
import freenet.config.Config;
import freenet.config.SubConfig;
import freenet.keys.FreenetURI;
import freenet.node.fcp.FCPServer;
import freenet.node.fcp.NotAllowedException;
import freenet.pluginmanager.FredPlugin;
import freenet.pluginmanager.FredPluginHTTP;
import freenet.pluginmanager.FredPluginThreadless;
import freenet.pluginmanager.PluginHTTPException;
import freenet.pluginmanager.PluginRespirator;
import freenet.support.HTMLNode;
import freenet.support.Logger;
import freenet.support.api.HTTPRequest;

public class ThawIndexBrowser implements FredPlugin, FredPluginThreadless, FredPluginHTTP {

	public static String SELF_URI = "/plugins/plugins.ThawIndexBrowser.ThawIndexBrowser/";

	private PluginRespirator pr;

	private PageMaker pm;

	private HighLevelSimpleClient client;
	
	private FCPServer fcp;

	public void runPlugin(PluginRespirator pr2) {

		Logger.error(this, "Start");

		pr = pr2;

		// create pagemaker

		Config nc = pr.getNode().config;
		SubConfig fc = nc.get("fproxy");
		String cssName = fc.getString("css");

		pm = new PageMaker(cssName);

		pm.addNavigationLink("/", "Fproxy", "Back to Fpoxy", false, null);

		client = pr.getHLSimpleClient();
		
		fcp = pr.getNode().clientCore.getFCPServer();

	}

	public void terminate() {
	}

	private HTMLNode createErrorBox(String title, String errmsg) {
		HTMLNode errorBox = pm.getInfobox("infobox-alert", title);
		errorBox.addChild("#", errmsg);
		return errorBox;
	}

	public String handleHTTPGet(HTTPRequest request) throws PluginHTTPException {
		String uri = request.getParam("key");
		if ((uri.trim().length() == 0)) {
			return makeUriPage();
		}
		return makeIndexPage(uri, false);
	}

	public String handleHTTPPost(HTTPRequest request) throws PluginHTTPException {
		String pass = request.getPartAsString("formPassword", 32);
		if ((pass.length() == 0) || !pass.equals(pr.getNode().clientCore.formPassword)) {
			return makeErrorPage("Buh! Invalid form password");
		}
		String uri = request.getPartAsString("key", 1024);
		if ((uri.trim().length() == 0)) {
			return makeUriPage();
		}
		
		if (request.getPartAsString("add", 128).length() > 0) {
			String downloadkey = request.getPartAsString("uri", 1024);
			try {
				fcp.makePersistentGlobalRequest(new FreenetURI(downloadkey), null, "forever", "disk");
			} catch (MalformedURLException e) {
				Logger.error(this, "TODO", e);
			} catch (NotAllowedException e) {
				Logger.error(this, "TODO", e);
			}
			return makeIndexPage(uri, false);
		} else {
			return makeIndexPage(uri, request.getPartAsString("addall", 128).length() > 0);
		}
	}

	public String handleHTTPPut(HTTPRequest request) throws PluginHTTPException {
		return makeUriPage();
	}

	/* pages */
	private String makeUriPage() {
		HTMLNode pageNode = pm.getPageNode("Index Browser", null);
		HTMLNode contentNode = pm.getContentNode(pageNode);
		contentNode.addChild(createUriBox());
		return pageNode.generate();
	}

	private String makeErrorPage(String error) {
		return makeErrorPage("ERROR", error);
	}

	private String makeErrorPage(String title, String error) {
		HTMLNode pageNode = pm.getPageNode("Index Browser", null);
		HTMLNode contentNode = pm.getContentNode(pageNode);
		contentNode.addChild(createErrorBox(title, error));
		contentNode.addChild(createUriBox());
		return pageNode.generate();
	}

	private String makeErrorPage(String title, String error, String newUri) {
		HTMLNode pageNode = pm.getPageNode("Index Browser", null);
		HTMLNode contentNode = pm.getContentNode(pageNode);
		HTMLNode errorBox = createErrorBox(title, error);
		errorBox.addChild("BR");
		errorBox.addChild(new HTMLNode("a", "href", SELF_URI + "?key=" + newUri, newUri));
		contentNode.addChild(errorBox);
		contentNode.addChild(createUriBox());
		return pageNode.generate();
	}

	private String makeIndexPage(String index, boolean add) {

		try {
			FreenetURI uri = new FreenetURI(index);
			FetchResult content = client.fetch(uri, 90000);
			String mime = content.getMimeType();
			if (!"application/x-freenet-index".equals(mime)) {
				return makeErrorPage("Wrong mime type: " + mime, "Expectedmime type \"application/x-freenet-index\", but found \""
						+ mime + "\".");
			}

			// data here, parse xml

			Builder builder = new Builder();

			Document doc = builder.build(content.asBucket().getInputStream());

			// now print the result...

			return printIndexPage(uri, doc, add);

		} catch (MalformedURLException e) {
			Logger.error(this, "Invalid URI: " + index, e);
			return makeErrorPage("Invalid URI: " + index, "The given freenet key is invalid");
		} catch (FetchException e) {
			Logger.error(this, "Fetch failed for: " + index, e);
			switch (e.mode) {
				case FetchException.PERMANENT_REDIRECT:
				case FetchException.TOO_MANY_PATH_COMPONENTS:
					return makeErrorPage("Fetch failed for: " + index, "(Code: " + e.mode + ") " + e.getLocalizedMessage(),
							e.newURI.toString());
				case FetchException.DATA_NOT_FOUND:
				case FetchException.ROUTE_NOT_FOUND:
				case FetchException.REJECTED_OVERLOAD:	
				case FetchException.ALL_DATA_NOT_FOUND:
					return makeErrorPage("Fetch failed for: " + index, "(Code: " + e.mode + ") " + e.getLocalizedMessage(), index);
				default:
					return makeErrorPage("Fetch failed for: " + index, "(Code: " + e.mode + ") " + e.getLocalizedMessage());
			}
		} catch (IOException e) {
			Logger.error(this, "IOError", e);
			return makeErrorPage("IOError", "IOError while processing " + index + ": " + e.getLocalizedMessage());
		} catch (ValidityException e) {
			Logger.error(this, "DEBUG", e);
			return makeErrorPage("Parser error", "Error while processing " + index + ": " + e.getLocalizedMessage());
		} catch (ParsingException e) {
			Logger.error(this, "DEBUG", e);
			return makeErrorPage("Parser error", "Error while processing " + index + ": " + e.getLocalizedMessage());
		} catch (Exception e) {
			Logger.error(this, "DEBUG", e);
			return makeErrorPage("Error while processing " + index + ": " + e.getLocalizedMessage());
		}
	}

	/* page utils */
	private HTMLNode createUriBox() {
		HTMLNode browseBox = pm.getInfobox("Open an Index");
		HTMLNode browseContent = pm.getContentNode(browseBox);
		// browseContent.addChild("#", "Display the top level chunk as
		// hexprint");
		HTMLNode browseForm = pr.addFormChild(browseContent, SELF_URI, "uriForm");
		browseForm.addChild("#", "Index to explore: \u00a0 ");
		browseForm.addChild("input", new String[] { "type", "name", "size" }, new String[] { "text", "key", "70" });
		browseForm.addChild("#", "\u00a0");
		browseForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "debug", "Explore!" });
		return browseBox;
	}

	private String printIndexPage(FreenetURI uri, Document doc, boolean add) {
		HTMLNode pageNode = pm.getPageNode("Index Browser", null);
		HTMLNode contentNode = pm.getContentNode(pageNode);

		Element root = doc.getRootElement();
		Element header = root.getFirstChildElement("header");

		HTMLNode titleBox = pm.getInfobox("Index: " + uri);
		Element titelelement = header.getFirstChildElement("title");
		if (titelelement != null) {
			titleBox.addChild("#", "Titel: \u00a0 " + titelelement.getValue());
			titleBox.addChild("BR");
		}

		Element clientelement = header.getFirstChildElement("client");
		if (clientelement != null) {
			titleBox.addChild("#", "Client: \u00a0 " + clientelement.getValue());
			titleBox.addChild("BR");
		}

		Element dateelement = header.getFirstChildElement("date");
		if (dateelement != null) {
			titleBox.addChild("#", "Date: \u00a0 " + dateelement.getValue());
			titleBox.addChild("BR");
		}

		contentNode.addChild(titleBox);
		
		Element indizies = root.getFirstChildElement("indexes");

		if (indizies.getChildCount() > 0) {
			HTMLNode indexBox = pm.getInfobox("Links:");
			Elements es = indizies.getChildElements();

			for (int i = 0; i < es.size(); i++) {
				Element e = es.get(i);
				indexBox.addChild(new HTMLNode("a", "href", SELF_URI + "?key=" + e.getAttribute("key").getValue(), e.getAttribute(
						"key").getValue()));
				indexBox.addChild("BR");
			}
			contentNode.addChild(indexBox);
		}

		Element files = root.getFirstChildElement("files");

		if (files.getChildCount() > 0) {
			HTMLNode fileBox = pm.getInfobox("Files:");
			
			HTMLNode table = new HTMLNode("table", "class", "requests");
			HTMLNode headerRow = table.addChild("tr", "class", "table-header");
			headerRow.addChild("th");
			headerRow.addChild("th", "Key/Name");
			headerRow.addChild("th", "Mimetype");
			headerRow.addChild("th", "Size");		
			
			Elements es = files.getChildElements();

			for (int i = 0; i < es.size(); i++) {
				Element e = es.get(i);
				HTMLNode fileRow = table.addChild("tr");
				String s = e.getAttribute("key").getValue();
				String s1;
				try {
					FreenetURI u = new FreenetURI(s);
					if (add) {
						try {
							fcp.makePersistentGlobalRequest(u, null, "forever", "disk");
						} catch (NotAllowedException e1) {
							Logger.error(this, "DEBUG", e1);
						}
						
					}
					if (s.length() > 100) {
						s1 = s.substring(0, 12);
						s1 += "...";
						s1 += s.substring(85);
						//s = s1;
					} else {
						s1 = s;
					}
					fileRow.addChild(createAddCell(s, uri.toString()));
					fileRow.addChild(createCell(new HTMLNode("a", "href", "/?key=" + s, s1)));
				} catch (MalformedURLException e1) {
					fileRow.addChild(new HTMLNode("td"));
					fileRow.addChild(createCell(new HTMLNode("#", s)));
				}

				fileRow.addChild(createCell(new HTMLNode("#", e.getAttribute("mime").getValue())));
				fileRow.addChild(createCell(new HTMLNode("#", e.getAttribute("size").getValue())));
			}
			HTMLNode fileRow = table.addChild("tr");
			fileRow.addChild(createAddAllCell(uri.toString()));
			fileBox.addChild(table);
			contentNode.addChild(fileBox);
		}
		contentNode.addChild(createUriBox());
		return pageNode.generate();
	}
	
	private HTMLNode createAddCell(String key, String uri) {
		HTMLNode deleteNode = new HTMLNode("td");
		HTMLNode deleteForm = pr.addFormChild(deleteNode, SELF_URI, "addForm-" + key.hashCode());
		deleteForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "uri", key });
		deleteForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "key", uri });
		deleteForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "add", "Download" });
		return deleteNode;
	}
	
	private HTMLNode createAddAllCell(String uri) {
		HTMLNode deleteNode = new HTMLNode("td");
		HTMLNode deleteForm = pr.addFormChild(deleteNode, SELF_URI, "addForm-all");
		deleteForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "key", uri });
		deleteForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "addall", "Download all" });
		return deleteNode;
	}

	
	private HTMLNode createCell(HTMLNode node) {
		HTMLNode cell = new HTMLNode("td");
		cell.addChild(node);
		return cell;
	}

}

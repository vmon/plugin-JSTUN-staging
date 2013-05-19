package plugins.JSTUN;

import java.lang.reflect.Method;
import java.net.BindException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Vector;
import java.util.Random;

import org.tanukisoftware.wrapper.WrapperManager;

import plugins.JSTUN.de.javawi.jstun.test.DiscoveryInfo;
import plugins.JSTUN.de.javawi.jstun.test.DiscoveryTest_;
import freenet.clients.http.PageNode;
import freenet.pluginmanager.DetectedIP;
import freenet.pluginmanager.FredPlugin;
import freenet.pluginmanager.FredPluginHTTP;
import freenet.pluginmanager.FredPluginIPDetector;
import freenet.pluginmanager.FredPluginRealVersioned;
import freenet.pluginmanager.FredPluginThreadless;
import freenet.pluginmanager.FredPluginVersioned;
import freenet.pluginmanager.PluginHTTPException;
import freenet.pluginmanager.PluginRespirator;
import freenet.support.HTMLNode;
import freenet.support.Logger;
import freenet.support.api.HTTPRequest;

// threadless in the sense that it doesn't need a thread running all the time.
// but getAddress() can and will block!
public class JSTUN implements FredPlugin, FredPluginIPDetector, FredPluginThreadless, FredPluginHTTP, FredPluginVersioned, FredPluginRealVersioned {

	// From http://www.voip-info.org/wiki-STUN
	String[] publicSTUNServers = new String[] {
			"stun.voipbuster.com",
			"stun.ideasip.com",
			"stun01.sipphone.com",
			"stun.softjoys.com",
			"stun.voipbuster.org",
			"stun.voxgratia.org",
			"stun.xten.com"
	};
	
	private DiscoveryInfo reportedData;
	private PluginRespirator pr;
	private boolean hasRunTestBeenCalled = false;
	
	DetectedIP[] runTest(InetAddress iaddress) {
		this.hasRunTestBeenCalled = true;
		Random r = new Random(); // FIXME use something safer?
		Vector v = new Vector(publicSTUNServers.length);
		Vector out = new Vector();
		int countLikely = 0;
		int countUnlikely = 0;
		for(int i=0;i<publicSTUNServers.length;i++)
			v.add(publicSTUNServers[i]);
		while(!v.isEmpty()) {
			if(WrapperManager.hasShutdownHookBeenTriggered()) return null;
			String stunServer = (String) v.remove(r.nextInt(v.size()));
			try {
				DiscoveryTest_ test = new DiscoveryTest_(iaddress, stunServer, 3478);
				// iphone-stun.freenet.de:3478
				// larry.gloo.net:3478
				// stun.xten.net:3478
				reportedData = test.test();
				if(((reportedData.isBlockedUDP() || reportedData.isError()) && !v.isEmpty())) {
					Logger.error(this, "Server unreachable?: "+stunServer);
					continue;
				}
				Logger.normal(this, "Successful STUN discovery from "+stunServer+"!:" + reportedData+" likely detections: "+countLikely+" unlikely detections "+countUnlikely+" remaining "+v.size());
				System.err.println("Successful STUN discovery from "+stunServer+"!:" + reportedData+" likely detections: "+countLikely+" unlikely detections "+countUnlikely+" remaining "+v.size());
				DetectedIP ip = convert(reportedData);
				if(ip == null) {
					Logger.normal(this, "Failed to parse reported data, skipping server "+stunServer);
					continue;
				}
				out.add(ip);
				if(ip.natType == DetectedIP.NO_UDP || ip.natType == DetectedIP.NOT_SUPPORTED || ip.natType == DetectedIP.SYMMETRIC_NAT || ip.natType == DetectedIP.SYMMETRIC_UDP_FIREWALL)
					countUnlikely++; // unlikely outcomes
				else
					countLikely++;
				if(countUnlikely >= 3 || countLikely >= 2 || v.isEmpty()) return (DetectedIP[])out.toArray(new DetectedIP[out.size()]);
			} catch (BindException be) {
				System.err.println(iaddress.toString() + ": " + be.getMessage());
			} catch (UnknownHostException e) {
				System.err.println("Could not find the STUN server "+stunServer+" : "+e+" - DNS problems? Trying another...");
			} catch (SocketException e) {
				System.err.println("Could not connect to the STUN server: "+stunServer+" : "+e+" - trying another...");
			} catch (Exception e) {
				System.err.println("Failed to run STUN to server "+stunServer+": "+e+" - trying another, report if persistent");
				e.printStackTrace();
			}
		}
		System.err.println("STUN failed: likely detections="+countLikely+" unlikely detections="+countUnlikely);
		return null;
	}
	
	private DetectedIP convert(DiscoveryInfo info) {
		InetAddress addr = info.getPublicIP();
		if(addr == null || addr.isLinkLocalAddress() || addr.isSiteLocalAddress())
			return null;
		if(info.isError())
			return null;
		if(info.isOpenAccess())
			return new DetectedIP(addr, DetectedIP.FULL_INTERNET);
		if(info.isBlockedUDP())
			return new DetectedIP(addr, DetectedIP.NO_UDP);
		if(info.isFullCone())
			return new DetectedIP(addr, DetectedIP.FULL_CONE_NAT);
		if(info.isRestrictedCone())
			return new DetectedIP(addr, DetectedIP.RESTRICTED_CONE_NAT);
		if(info.isPortRestrictedCone())
			return new DetectedIP(addr, DetectedIP.PORT_RESTRICTED_NAT);
		if(info.isSymmetricCone())
			return new DetectedIP(addr, DetectedIP.SYMMETRIC_NAT);
		if(info.isSymmetricUDPFirewall())
			return new DetectedIP(addr, DetectedIP.SYMMETRIC_UDP_FIREWALL);
		return new DetectedIP(addr, DetectedIP.NOT_SUPPORTED);
	}

	public DetectedIP[] getAddress() {
		Enumeration<NetworkInterface> ifaces;
		try {
			ifaces = NetworkInterface.getNetworkInterfaces();
		} catch (SocketException e1) {
			System.err.println("Caught "+e1);
			e1.printStackTrace();
			return null;
		}
		while (ifaces.hasMoreElements()) {
			if(WrapperManager.hasShutdownHookBeenTriggered()) return null;
			NetworkInterface iface = ifaces.nextElement();
			Enumeration<InetAddress> iaddresses = iface.getInetAddresses();
			while (iaddresses.hasMoreElements()) {
				InetAddress iaddress = iaddresses.nextElement();
				if (!iaddress.isLoopbackAddress() && !iaddress.isLinkLocalAddress()) {
					Thread detector = new DetectorThread(iaddress);
					synchronized(this) {
						detectors.add(detector);
					}
					try {
						detector.start();
					} catch (Throwable t) {
						synchronized(this) {
							detectors.remove(detector);
						}
					}
				}
			}
		}
		synchronized(this) {
			while(true) {
				if(detectors.isEmpty()) {
					if(detected.isEmpty()) {
						System.err.println("STUN failed to detect IP addresses");
						return null;
					}
					DetectedIP[] ips = (DetectedIP[]) detected.toArray(new DetectedIP[detected.size()]);
					return ips;
				}
				try {
					wait();
				} catch (InterruptedException e) {
					// Check whether finished
				}
			}
		}

	}

	private final HashSet detected = new HashSet();
	private final HashSet detectors = new HashSet();
	
	class DetectorThread extends Thread {
		
		DetectorThread(InetAddress addr) {
			this.startAddress = addr;
			this.setDaemon(true);
			this.setName("STUN IP detector for "+addr);
		}
		
		final InetAddress startAddress;
		
		public void run() {
			DetectedIP[] ip;
			try {
				ip = runTest(startAddress);
			} catch (Throwable t) {
				ip = null;
				System.err.println("Caught "+t);
				t.printStackTrace();
			}
			synchronized(JSTUN.this) {
				detectors.remove(this);
				if(ip != null) {
					for(int i=0;i<ip.length;i++)
						detected.add(ip[i]);
				}
				JSTUN.this.notifyAll();
			}
		}
	}
	
	public void terminate() {
		return;
	}

	public void runPlugin(PluginRespirator pr) {
		this.pr = pr;
	}

	public String handleHTTPGet(HTTPRequest request) throws PluginHTTPException {
		PageNode page = pr.getPageMaker().getPageNode("JSTUN plugin", false, null);
		HTMLNode pageNode = page.outer;
		HTMLNode contentNode = page.content;

		if(reportedData == null) {
			if(hasRunTestBeenCalled) {
				HTMLNode jSTUNReportInfobox = contentNode.addChild("div", "class", "infobox infobox-warning");
				HTMLNode jSTUNReportInfoboxHeader = jSTUNReportInfobox.addChild("div", "class", "infobox-header");
				HTMLNode jSTUNReportInfoboxContent = jSTUNReportInfobox.addChild("div", "class", "infobox-content");

				jSTUNReportInfoboxHeader.addChild("#", "JSTUN detection report");

				jSTUNReportInfoboxContent.addChild("#", "The plugin hasn't managed to contact any server yet.");
			} else {
				HTMLNode jSTUNReportInfobox = contentNode.addChild("div", "class", "infobox infobox-normal");
				HTMLNode jSTUNReportInfoboxHeader = jSTUNReportInfobox.addChild("div", "class", "infobox-header");
				HTMLNode jSTUNReportInfoboxContent = jSTUNReportInfobox.addChild("div", "class", "infobox-content");

				jSTUNReportInfoboxHeader.addChild("#", "JSTUN detection report");

				jSTUNReportInfoboxContent.addChild("#", "There is no need for the plugin to determine your ip address: the node knows it.");
			}
		} else {
			HTMLNode jSTUNReportErrorInfobox = contentNode.addChild("div", "class", "infobox infobox-normal");
			HTMLNode jSTUNReportInfoboxHeader = jSTUNReportErrorInfobox.addChild("div", "class", "infobox-header");
			HTMLNode jSTUNReportInfoboxContent = jSTUNReportErrorInfobox.addChild("div", "class", "infobox-content");

			jSTUNReportInfoboxHeader.addChild("#", "JSTUN detection report");

			jSTUNReportInfoboxContent.addChild("#", "The plugin has reported the following data to the node:");
			HTMLNode data = jSTUNReportInfoboxContent.addChild("pre");
			data.addChild("#", reportedData.toString());
		}
		return pageNode.generate();
	}

	public String handleHTTPPost(HTTPRequest request)
			throws PluginHTTPException {
		return null;
	}

	public String handleHTTPPut(HTTPRequest request) throws PluginHTTPException {
		return null;
	}

	public String getVersion() {
		return "1.2";
	}

	public long getRealVersion() {
		return 3;
	}
}

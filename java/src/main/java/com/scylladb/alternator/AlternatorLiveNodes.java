package com.scylladb.alternator;

import java.util.HashSet;
import java.util.List;
import java.util.ArrayList;
import java.util.Scanner;
import java.util.Arrays;
import java.util.Set;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.URISyntaxException;
import java.net.MalformedURLException;
import java.net.HttpURLConnection;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.logging.Level;

/**
 * Maintains and automatically updates a list of known live Alternator nodes.
 * Live Alternator nodes should answer alternatorScheme (http or https)
 * requests on port alternatorPort. One of these livenodes will be used,
 * at round-robin order, for every connection.  The list of live nodes starts
 * with one or more known nodes, but then a thread periodically replaces this
 * list by an up-to-date list retrieved from making a "/localnodes" requests
 * to one of these nodes.
 */
public class AlternatorLiveNodes extends Thread {
    private static final long BAD_NODES_RESET_INTERVAL_MILLIS = TimeUnit.HOURS.toMillis(1);
    private String alternatorScheme;
    private int alternatorPort;

    private List<String> liveNodes;
    private int nextLiveNodeIndex;
    private Set<String> badNodes = new HashSet<>();
    private long badNodesListLastResetMillis = System.currentTimeMillis();

    private static Logger logger = Logger.getLogger(AlternatorLiveNodes.class.getName());

    @Override
    public void run() {
        logger.log(Level.INFO, "AlternatorLiveNodes thread starting");
        for (;;) {
            updateLiveNodes();
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                logger.log(Level.INFO, "AlternatorLiveNodes thread interrupted and stopping");
                return;
            }
        }
    }

    // A private constructor. Use the create() functions below instead, as
    // they also start the thread after creating the object.
    private AlternatorLiveNodes(String alternatorScheme, List<String> liveNodes, int alternatorPort) {
        this.alternatorScheme = alternatorScheme;
        this.liveNodes = liveNodes;
        this.alternatorPort = alternatorPort;
        this.nextLiveNodeIndex = 0;
    }

    public static AlternatorLiveNodes create(String scheme, List<String> hosts, int port) {
        AlternatorLiveNodes ret = new AlternatorLiveNodes(scheme, hosts, port);
        // setDaemon(true) allows the program to exit even if the thread is
        // is still running.
        ret.setDaemon(true);
        ret.start();
        // Make sure
        return ret;
    }
    public static AlternatorLiveNodes create(URI uri) {
        return create(uri.getScheme(), Arrays.asList(uri.getHost()), uri.getPort());
    }

    public synchronized String nextNode() {
        String node = liveNodes.get(nextLiveNodeIndex);
        logger.log(Level.FINE, "Using node " + node);
        nextLiveNodeIndex = (nextLiveNodeIndex + 1) % liveNodes.size();
        return node;
    }

    public URI nextAsURI() {
        try {
            return new URI(alternatorScheme, null, nextNode(), alternatorPort, "", null, null);
        } catch (URISyntaxException e) {
            // Can't happen with the empty path and other nulls we used above...
            logger.log(Level.WARNING, "nextAsURI", e);
            return null;
        }
    }

    public URL nextAsURL(String file, String nextNode) {
        try {
            return new URL(alternatorScheme, nextNode, alternatorPort, file);
        } catch (MalformedURLException e) {
            // Can only happen if alternatorScheme is an unknown one.
            logger.log(Level.WARNING, "nextAsURL", e);
            return null;
        }
    }

    // Utility function for reading the entire contents of an input stream
    // (which we assume will be fairly short)
    private static String streamToString(java.io.InputStream is) {
        Scanner s = new Scanner(is).useDelimiter("\\A");
        return s.hasNext() ? s.next() : "";
    }

    private void updateLiveNodes() {
        clearBadNodes();
        List<String> newHosts = new ArrayList<>();
        String nextNode = nextNode();
        URL url = nextAsURL("/localnodes", nextNode);
        try {
            // Note that despite this being called HttpURLConnection, it actually
            // supports HTTPS as well.
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            int responseCode = conn.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK)  {
                String response = streamToString(conn.getInputStream());
                // response looks like: ["127.0.0.2","127.0.0.3","127.0.0.1"]
                response = response.trim();
                response = response.substring(1, response.length() - 1);
                String[] list = response.split(",");
                for (String host : list) {
                    host = host.trim();
                    host = host.substring(1, host.length() - 1);
                    newHosts.add(host);
                }
            }
        } catch (IOException e) {
            logger.log(Level.FINE, "Request failed: " + url, e);
            badNodes.add(nextNode);
            logger.log(Level.WARNING, "Marked node " + nextNode + " as bad");
        }
        newHosts.removeAll(badNodes);
        if (!newHosts.isEmpty()) {
            synchronized(this) {
                this.liveNodes = newHosts;
                this.nextLiveNodeIndex = 0;
            }
            logger.log(Level.FINE, "Updated hosts to " + this.liveNodes);
            if (!badNodes.isEmpty()) {
                logger.log(Level.FINE, "Bad nodes " + badNodes);
            }
        }
    }

    private void clearBadNodes() {
        if (System.currentTimeMillis() - badNodesListLastResetMillis > BAD_NODES_RESET_INTERVAL_MILLIS) {
            badNodes.clear();
            badNodesListLastResetMillis = System.currentTimeMillis();
            logger.log(Level.FINE, "Cleared bad nodes list");
        }
    }
}

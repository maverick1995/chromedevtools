// Copyright (c) 2009 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.sdk.internal;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.chromium.sdk.Browser;
import org.chromium.sdk.BrowserTab;
import org.chromium.sdk.TabDebugEventListener;
import org.chromium.sdk.UnsupportedVersionException;
import org.chromium.sdk.Version;
import org.chromium.sdk.internal.tools.ToolHandler;
import org.chromium.sdk.internal.tools.ToolName;
import org.chromium.sdk.internal.tools.devtools.DevToolsServiceHandler;
import org.chromium.sdk.internal.tools.devtools.DevToolsServiceHandler.TabIdAndUrl;
import org.chromium.sdk.internal.transport.Connection;
import org.chromium.sdk.internal.transport.Message;
import org.chromium.sdk.internal.transport.Connection.NetListener;

/**
 * A thread-safe implementation of the Browser interface.
 */
public class BrowserImpl implements Browser {

  private static final Logger LOGGER = Logger.getLogger(BrowserImpl.class.getName());

  public static final int OPERATION_TIMEOUT_MS = 3000;

  public static final Version INVALID_VERSION = new Version(0, 0);

  /**
   * The protocol version supported by this SDK implementation.
   */
  public static final Version PROTOCOL_VERSION = new Version(0, 1);

  /**
   * One single session supported by browser.
   * TODO(peter.rybin): make session replaceable
   */
  private final Session permanentSession;

  BrowserImpl(Connection connection) {
    permanentSession = new Session(connection);
  }

  public void connect() throws IOException, UnsupportedVersionException {
    permanentSession.connect();
  }

  public void disconnect() {
    permanentSession.disconnect();
  }

  public TabFetcher createTabFetcher() {
    return permanentSession.getTabFetcher();
  }

  /**
   * Object that lives during one connection period. Browser should be able to
   * reconnect (because we want to support attach-detach-attach sequence). On
   * reconnect new session should be created. Each browser tab should be linked
   * to a particular session.
   */
  public class Session {

    /** A mapping of tab IDs to BrowserTabImpls. */
    private final Map<Integer, BrowserTabImpl> tabUidToTabImpl =
        Collections.synchronizedMap(new HashMap<Integer, BrowserTabImpl>());

    /** The DevTools service handler for the browser. */
    private volatile DevToolsServiceHandler devToolsHandler;

    /** The browser connection (gets opened in the connect() call). */
    private final Connection connection;

    private boolean isNetworkSetUp;

    Session(Connection connection) {
      this.connection = connection;
    }

    public TabFetcher getTabFetcher() {
      return new TabFetcherImpl();
    }

    private void removeDetachedTabs() {
      for (Iterator<Map.Entry<Integer, BrowserTabImpl>> it = tabUidToTabImpl.entrySet().iterator();
           it.hasNext(); ) {
        Map.Entry<Integer, BrowserTabImpl> entry = it.next();
        if (!entry.getValue().isAttached()) {
          it.remove();
        }
      }
    }

    BrowserTabImpl getBrowserTab(int tabUid) {
      return tabUidToTabImpl.get(tabUid);
    }

    public Connection getConnection() {
      return connection;
    }

    public void disconnect() {
      getConnection().close();
    }

    public void sessionTerminated(int tabId) {
      tabUidToTabImpl.remove(tabId);
      if (!hasAttachedTabs() && getConnection().isConnected()) {
        disconnect();
      }
    }

    private boolean hasAttachedTabs() {
      for (BrowserTabImpl tab : tabUidToTabImpl.values()) {
        if (tab.isAttached()) {
          return true;
        }
      }
      return false;
    }

    // TODO(peter.rybin): make sure the connection is closed if we fail here.
    public void connect() throws UnsupportedVersionException, IOException {
      if (ensureService()) {
        // No need to check the version for an already established connection.
        return;
      }
      Version serverVersion;
      try {
        serverVersion = devToolsHandler.version(OPERATION_TIMEOUT_MS);
      } catch (TimeoutException e) {
        throw new IOException("Failed to get protocol version from remote", e);
      }
      if (serverVersion == null ||
          !BrowserImpl.PROTOCOL_VERSION.isCompatibleWithServer(serverVersion)) {
        isNetworkSetUp = false;
        throw new UnsupportedVersionException(BrowserImpl.PROTOCOL_VERSION, serverVersion);
      }
    }

    private boolean ensureService() throws IOException {
      if (!isNetworkSetUp) {
        devToolsHandler = new DevToolsServiceHandler(connection);
        connection.setNetListener(netListener);
        this.isNetworkSetUp = true;
      }
      boolean wasConnected = connection.isConnected();
      if (!wasConnected) {
        connection.start();
      }
      return wasConnected;
    }

    // exposed for testing
    /* package private */ DevToolsServiceHandler getDevToolsServiceHandler() {
      return devToolsHandler;
    }

    private void checkConnection() {
      if (connection == null || !connection.isConnected()) {
        throw new IllegalStateException("connection is not started");
      }
    }

    private final NetListener netListener = new NetListener() {
      public void connectionClosed() {
        devToolsHandler.onDebuggerDetached();
        // Use a copy to avoid the underlying map modification in #sessionTerminated
        // invoked through #onDebuggerDetached
        ArrayList<BrowserTabImpl> tabsCopy = new ArrayList<BrowserTabImpl>(tabUidToTabImpl.values());
        for (Iterator<BrowserTabImpl> it = tabsCopy.iterator(); it.hasNext();) {
          it.next().getDebugSession().onDebuggerDetached();
        }
      }

      public void messageReceived(Message message) {
        ToolName toolName = ToolName.forString(message.getTool());
        if (toolName == null) {
          LOGGER.log(Level.SEVERE, "Bad 'Tool' header received: {0}", message.getTool());
          return;
        }
        ToolHandler handler = null;
        switch (toolName) {
          case DEVTOOLS_SERVICE:
            handler = devToolsHandler;
            break;
          case V8_DEBUGGER:
            BrowserTabImpl tab = getBrowserTab(Integer.valueOf(message.getDestination()));
            if (tab != null) {
              handler = tab.getV8ToolHandler();
            }
            break;
          default:
            LOGGER.log(Level.SEVERE, "Unregistered handler for tool: {0}", message.getTool());
            return;
        }
        if (handler != null) {
          handler.handleMessage(message);
        } else {
          LOGGER.log(
              Level.SEVERE,
              "null handler for tool: {0}, destination: {1}",
              new Object[] {message.getTool(), message.getDestination()});
        }
      }
      public void eosReceived() {
      }
    };

    public BrowserImpl getBrowser() {
      return BrowserImpl.this;
    }

    /**
     * Not fully working implementation of {@link TabFetcher}: it doesn't release connection.
     * TODO(peter.rybin): implement connection use accounting and {@link #dismiss()} method.
     */
    private class TabFetcherImpl implements TabFetcher {
      public List<? extends TabConnector> getTabs() {
        checkConnection();
        removeDetachedTabs();
        List<TabIdAndUrl> entries = devToolsHandler.listTabs(OPERATION_TIMEOUT_MS);
        List<TabConnectorImpl> browserTabs = new ArrayList<TabConnectorImpl>(entries.size());
        for (TabIdAndUrl entry : entries) {
          BrowserTabImpl tab = tabUidToTabImpl.get(entry.id);
          if (tab == null || !tab.isAttached()) {
            tab = new BrowserTabImpl(entry.id, entry.url, Session.this);
            tabUidToTabImpl.put(entry.id, tab);
          }
          browserTabs.add(new TabConnectorImpl(tab));
        }
        return browserTabs;
      }

      public void dismiss() {
        // TODO(peter.rybin): implement this when we count clients.
      }
    }
  }

  private static class TabConnectorImpl implements TabConnector {
    private final BrowserTabImpl browserTab;

    TabConnectorImpl(BrowserTabImpl browserTab) {
      this.browserTab = browserTab;
    }

    public String getUrl() {
      return browserTab.getUrl();
    }

    public BrowserTab attach(TabDebugEventListener listener) {
      browserTab.attach(listener);
      return browserTab;
    }
  }

  Session getPermanentSessionForTest() {
    return permanentSession;
  }
}
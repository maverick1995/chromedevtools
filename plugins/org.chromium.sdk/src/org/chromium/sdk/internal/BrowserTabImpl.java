// Copyright (c) 2009 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.sdk.internal;

import org.chromium.sdk.BrowserTab;
import org.chromium.sdk.DebugEventListener;
import org.chromium.sdk.internal.tools.ToolHandler;
import org.chromium.sdk.internal.tools.v8.ChromeDevToolSessionManager;
import org.chromium.sdk.internal.tools.v8.ChromeDevToolSessionManager.AttachmentFailureException;

/**
 * A default, thread-safe implementation of the BrowserTab interface.
 */
public class BrowserTabImpl extends JavascriptVmImpl implements BrowserTab {

  /** Tab ID as reported by the DevTools server. */
  private final int tabId;

  /** The primary tab URL. */
  private final String url;

  /** The host BrowserImpl instance. */
  private final BrowserImpl browserImpl;

  /** The debug context instance for this tab. */
  private final DebugContextImpl context;
  
  private final ChromeDevToolSessionManager devToolSessionManager;

  /** The listener to report debug events to. */
  private DebugEventListener debugEventListener;

  public BrowserTabImpl(int tabId, String url, BrowserImpl browserImpl) {
    this.tabId = tabId;
    this.url = url;
    this.browserImpl = browserImpl;
    this.context = new DebugContextImpl(this);
    this.devToolSessionManager = new ChromeDevToolSessionManager(this, context);;
  }

  public String getUrl() {
    return url;
  }

  public int getId() {
    return tabId;
  }

  @Override
  public DebugContextImpl getDebugContext() {
    return context;
  }

  @Override
  public synchronized DebugEventListener getDebugEventListener() {
    return debugEventListener;
  }

  public BrowserImpl getBrowser() {
    return browserImpl;
  }

  public synchronized boolean attach(DebugEventListener listener) {
    Result result = null;
    try {
      result = devToolSessionManager.attachToTab();
    } catch (AttachmentFailureException e) {
      // fall through and return false
    }
    this.debugEventListener = listener;
    return Result.OK == result;
  }

  public boolean detach() {
    final Result result = devToolSessionManager.detachFromTab();
    return Result.OK == result;
  }

  public boolean isAttached() {
    return devToolSessionManager.isAttached();
  }

  public void sessionTerminated() {
    browserImpl.sessionTerminated(this.tabId);
  }

  public ToolHandler getV8ToolHandler() {
      return devToolSessionManager.getToolHandler();
  }
  
  @Override
  public ChromeDevToolSessionManager getSessionManager() {
    return devToolSessionManager;
  }
}

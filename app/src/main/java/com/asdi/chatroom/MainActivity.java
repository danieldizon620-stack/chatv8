package com.asdi.chatroom;

import android.Manifest;
import android.app.Activity;
import android.view.Menu;
import android.view.MenuItem;
import org.json.JSONArray;
import android.webkit.CookieManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Notification;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebResourceRequest;
import android.content.Intent;
import android.net.Uri;
import android.app.PendingIntent;
import android.app.AlertDialog;
import android.webkit.ValueCallback;
import android.provider.MediaStore;
import android.webkit.PermissionRequest;
import android.webkit.WebSettings;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

public class MainActivity extends Activity {
  private static final String DEFAULT_BASE = "http://100.69.232.118:8080/asdi_ticketing/";
  private String base = DEFAULT_BASE;
  private String chatUrl = DEFAULT_BASE + "chatroom.php";
  private static final String CHANNEL = "asdi_chat_messages";
  private WebView web;
  private TextView badge;
  private TextView footer;
  private FrameLayout root;
  private ValueCallback<Uri[]> fileCallback;
  private Uri pendingPhoto;
  private static final int FILE_PICKER_REQUEST = 620;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private long lastSeen = 0;
  private int unread = 0;
  private boolean initialized = false;
  private boolean active = false;
  private long lastTicketId = -1;
  private long lastGroupMessageId = -1;
  private long lastPrivateMessageId = -1;
  private boolean chatBaselineSet = false;
  private boolean returningFromLogin = false;
  private boolean ticketViewRequested = false;
  private static final String TICKET_CHANNEL = "asdi_new_tickets_v1";

  private final Runnable poll = new Runnable() {
    @Override public void run() {
      if(active && web != null && web.getUrl() != null && web.getUrl().startsWith(base)) {
        pollChat();
        pollTickets();
      }
      handler.postDelayed(this, 15000);
    }
  };

  @Override public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    if(getActionBar()!=null)getActionBar().hide();
    getWindow().setFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN,
                         android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN);
    chatUrl = base + "chatroom.php";
    lastSeen = getPreferences(0).getLong("last_seen_" + base, 0);
    NotificationManager manager = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
    if (Build.VERSION.SDK_INT >= 26) {
      NotificationChannel channel = new NotificationChannel(CHANNEL, "New chat messages", NotificationManager.IMPORTANCE_DEFAULT);
      channel.setDescription("ASDI Chatroom message alerts");
      channel.enableVibration(true);
      manager.createNotificationChannel(channel);
    }
    if (Build.VERSION.SDK_INT >= 26) {
      NotificationChannel ticketChannel = new NotificationChannel(TICKET_CHANNEL, "New service tickets", NotificationManager.IMPORTANCE_HIGH);
      ticketChannel.setDescription("Detailed alerts for newly created ASDI tickets");
      ticketChannel.enableVibration(true);
      manager.createNotificationChannel(ticketChannel);
    }
    lastTicketId = getPreferences(0).getLong("ticket_last_" + base, -1);
    lastGroupMessageId = getPreferences(0).getLong("chat_group_last_" + base, -1);
    lastPrivateMessageId = getPreferences(0).getLong("chat_private_last_" + base, -1);
    if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
      requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 101);
    }
    root = new FrameLayout(this);
    web = new WebView(this);
    FrameLayout.LayoutParams webParams = new FrameLayout.LayoutParams(-1, -1);
    webParams.bottomMargin = dp(42);
    root.addView(web, webParams);
    footer = new TextView(this);
    footer.setText("ASDI Chatroom v3.41  •  Created by dannyboi620     Log out  ↪");
    footer.setTextColor(0xFF263744);
    footer.setTextSize(11);
    footer.setGravity(Gravity.CENTER);
    footer.setBackgroundColor(0xFFF1F5F7);
    footer.setPadding(dp(6), 0, dp(6), 0);
    FrameLayout.LayoutParams fp = new FrameLayout.LayoutParams(-1, dp(42), Gravity.BOTTOM);
    root.addView(footer, fp);
    footer.setOnClickListener(v -> confirmLogout());
    badge = new TextView(this);
    badge.setTextColor(Color.WHITE);
    badge.setTextSize(13);
    badge.setGravity(Gravity.CENTER);
    badge.setMinWidth(dp(28));
    badge.setMinHeight(dp(28));
    badge.setPadding(dp(6), dp(2), dp(6), dp(2));
    GradientDrawable bg = new GradientDrawable();
    bg.setColor(0xFFE02030);
    bg.setCornerRadius(dp(40));
    badge.setBackground(bg);
    badge.setVisibility(View.GONE);
    FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.RIGHT);
    bp.setMargins(0, dp(12), dp(12), 0);
    root.addView(badge, bp);
    badge.setOnClickListener(v -> { clearBadge(); web.loadUrl(chatUrl); });
    // Photo capture is available from the chat composer attachment picker.
    // No floating button is displayed over the conversation.
    setContentView(root);

    web.getSettings().setJavaScriptEnabled(true);
    web.getSettings().setDomStorageEnabled(true);
    web.getSettings().setUseWideViewPort(true);
    web.getSettings().setLoadWithOverviewMode(false);
    web.getSettings().setTextZoom(100);
    web.setInitialScale(100);
    web.getSettings().setBuiltInZoomControls(false);
    web.getSettings().setDisplayZoomControls(false);
    web.getSettings().setAllowFileAccess(false);
    web.getSettings().setAllowContentAccess(true);
    android.webkit.CookieManager.getInstance().setAcceptCookie(true);
    web.addJavascriptInterface(new Object() {
      @JavascriptInterface public void onLogout(String status) {
        runOnUiThread(() -> {
          if (!"ok".equals(status)) {
            new AlertDialog.Builder(MainActivity.this)
              .setTitle("Log out failed")
              .setMessage("Could not end the server session. Check the ASDI Ticketing v3.34 update and your connection.")
              .setPositiveButton("OK", null).show();
            return;
          }
          returningFromLogin = false;
          clearBadge();
          CookieManager.getInstance().removeAllCookies(null);
          CookieManager.getInstance().flush();
          web.clearHistory();
          web.loadUrl(base + "login.php");
        });
      }
      @JavascriptInterface public void onMessages(String raw, String count) {
        try {
          final long newest = Long.parseLong(raw);
          final int incoming = Integer.parseInt(count);
          runOnUiThread(() -> updateMessages(newest, incoming));
        } catch (NumberFormatException ignored) { }
      }
      @JavascriptInterface public void onTickets(String json) {
        runOnUiThread(() -> handleTickets(json));
      }
      @JavascriptInterface public void onChatSummary(String json) {
        runOnUiThread(() -> handleChatSummary(json));
      }
    }, "ASDI");
    web.setWebChromeClient(new WebChromeClient() {
      @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
        if (fileCallback != null) fileCallback.onReceiveValue(null);
        fileCallback = callback;
        Intent picker = new Intent(Intent.ACTION_GET_CONTENT);
        picker.addCategory(Intent.CATEGORY_OPENABLE);
        picker.setType("*/*");
        String[] types = params.getAcceptTypes();
        if (types != null && types.length > 0 && !types[0].isEmpty()) {
          if (types.length == 1) picker.setType(types[0]);
          else picker.putExtra(Intent.EXTRA_MIME_TYPES, types);
        }
        Intent chooser = Intent.createChooser(picker, "Choose attachment or take photo");
        pendingPhoto = null;
        try {
          android.content.ContentValues values = new android.content.ContentValues();
          values.put(MediaStore.Images.Media.DISPLAY_NAME, "ASDI_photo_" + System.currentTimeMillis() + ".jpg");
          values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
          pendingPhoto = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
          if (pendingPhoto != null) {
            Intent capture = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            capture.putExtra(MediaStore.EXTRA_OUTPUT, pendingPhoto);
            capture.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            if (capture.resolveActivity(getPackageManager()) != null) chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{capture});
          }
        } catch (Exception ignored) { pendingPhoto = null; }
        try { startActivityForResult(chooser, FILE_PICKER_REQUEST); }
        catch (Exception e) { fileCallback.onReceiveValue(null); fileCallback = null; return false; }
        return true;
      }

    });
    web.setWebViewClient(new WebViewClient() {
      @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
        Uri u = request.getUrl();
        if (("http".equals(u.getScheme()) || "https".equals(u.getScheme())) && Uri.parse(base).getHost().equals(u.getHost()) && u.getPath() != null && u.getPath().startsWith(Uri.parse(base).getPath())) {
          if (isDashboardUrl(u.toString())) { view.loadUrl(chatUrl); return true; }
          return false;
        }
        try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
        return true;
      }
      @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
        // Avoid flashing the ticketing dashboard between login and Chatroom.
        if (url != null && url.startsWith(base) && isDashboardUrl(url)) {
          view.setVisibility(View.INVISIBLE);
          // After login the website normally redirects to index.php; reroute it
          // immediately, before the dashboard can flash or become visible.
          if (!returningFromLogin) {
            returningFromLogin = true;
            view.post(() -> view.loadUrl(chatUrl));
          }
        } else view.setVisibility(View.VISIBLE);
      }
      @Override public void onPageFinished(WebView view, String url) {
        if (url == null || !url.startsWith(base)) { view.setVisibility(View.VISIBLE); return; }
        applyMobileCss(view);
        if (isDashboardUrl(url)) {
          view.setVisibility(View.INVISIBLE);
          if (!returningFromLogin) {
            returningFromLogin = true;
            view.loadUrl(chatUrl);
          }
          return;
        }
        if (isLoginUrl(url)) {
          returningFromLogin = false;
          view.setVisibility(View.VISIBLE);
          return;
        }
        if (isChatUrl(url)) {
          returningFromLogin = false;
          ticketViewRequested = false;
          // Mark the actual chat page for scoped, Android-only CSS.
          enforceChatOnly(view);
          view.evaluateJavascript("(function(){if(window.matchMedia('(max-width:700px)').matches){var w=document.getElementById('asdi-chat-workspace');if(w&&!w.classList.contains('asdi-groups-hidden'))document.getElementById('asdi-hide-groups')?.click();}})()",null);
          // Preserve server-side Chrome layout; no Android-specific panel/composer overrides.
        }
        view.setVisibility(View.VISIBLE);
      }
    });
    web.loadUrl(chatUrl);
    handleIntent(getIntent());
    handler.postDelayed(poll, 10000);
  }

  @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    super.onActivityResult(requestCode, resultCode, data);
    if (requestCode != FILE_PICKER_REQUEST || fileCallback == null) return;
    Uri[] result = null;
    if (resultCode == RESULT_OK) {
      if (data != null && data.getData() != null) result = new Uri[]{data.getData()};
      else if (pendingPhoto != null) result = new Uri[]{pendingPhoto};
    }
    if (result == null && pendingPhoto != null) try { getContentResolver().delete(pendingPhoto, null, null); } catch (Exception ignored) { }
    fileCallback.onReceiveValue(result);
    fileCallback = null;
    pendingPhoto = null;
  }

  private boolean isDashboardUrl(String url) {
    if(url == null || !url.startsWith(base)) return false;
    String path = Uri.parse(url).getLastPathSegment();
    return url.equals(base) || path == null || "index.php".equals(path) || "dashboard.php".equals(path);
  }
  private boolean isLoginUrl(String url) {
    return url != null && url.startsWith(base) && "login.php".equals(Uri.parse(url).getLastPathSegment());
  }
  private boolean isChatUrl(String url) {
    return url != null && url.startsWith(base) && "chatroom.php".equals(Uri.parse(url).getLastPathSegment());
  }

  private void installMobileChatPanels(WebView view) {
    String js = "(function(){if(!location.pathname.endsWith('/chatroom.php'))return;" +
      "var room=document.getElementById('asdi-chat-workspace');if(!room||room.dataset.mobilePanelsReady)return;" +
      "room.dataset.mobilePanelsReady='1';" +
      "room.classList.add('container-fluid','p-0');var main=room.querySelector('.asdi-room-main');if(main)main.classList.add('d-flex','flex-column','min-vh-100');" +
      "var groups=document.getElementById('asdi-show-groups'),users=document.getElementById('asdi-show-users');" +
      "var groupPanel=room.querySelector('.asdi-room-list'),userPanel=room.querySelector('.asdi-employees-panel');" +
      "if(!groups||!users)return;" +
      "groups.hidden=false;users.hidden=false;" +
      "function close(){room.classList.remove('asdi-mobile-groups-open','asdi-mobile-users-open');}" +
      "groups.addEventListener('click',function(e){e.stopImmediatePropagation();var open=!room.classList.contains('asdi-mobile-groups-open');close();if(open)room.classList.add('asdi-mobile-groups-open');if(groupPanel)groupPanel.setAttribute('aria-hidden',String(!open));},true);" +
      "users.addEventListener('click',function(e){e.stopImmediatePropagation();var open=!room.classList.contains('asdi-mobile-users-open');close();if(open)room.classList.add('asdi-mobile-users-open');if(userPanel)userPanel.setAttribute('aria-hidden',String(!open));},true);" +
      "room.querySelectorAll('#asdi-hide-groups,#asdi-hide-users').forEach(function(b){b.addEventListener('click',function(e){e.stopImmediatePropagation();close();},true);});" +
      "room.addEventListener('click',function(e){if(e.target.closest('.asdi-room-main')&&!e.target.closest('#asdi-show-groups,#asdi-show-users'))close();});" +
      "})()";
    view.evaluateJavascript(js,null);
  }

  private void installComposerFix(WebView view) {
    String js = "(function(){if(!location.pathname.endsWith('/chatroom.php'))return;" +
      "var form=document.getElementById('asdi-room-send');if(!form||form.dataset.asdiComposerFixed)return;" +
      "form.dataset.asdiComposerFixed='1';" +
      "var menu=document.getElementById('asdi-attach-options'),toggle=document.getElementById('asdi-attach-menu-button');" +
      "var emoji=document.getElementById('asdi-room-emoji-panel'),emojiButton=document.getElementById('asdi-room-emoji-button');" +
      "function close(){if(menu){menu.hidden=true;}if(toggle)toggle.setAttribute('aria-expanded','false');}" +
      "document.addEventListener('pointerdown',function(e){if(menu&&!menu.hidden&&!menu.contains(e.target)&&e.target!==toggle&&!toggle.contains(e.target))close();},true);" +
      "document.addEventListener('keydown',function(e){if(e.key==='Escape')close();});" +
      "form.addEventListener('submit',function(){close();},true);" +
      "var text=form.querySelector('textarea[name=message]');if(text){text.addEventListener('focus',close);}" +
      "if(emojiButton)emojiButton.addEventListener('click',close,true);" +
      "var send=form.querySelector('button[type=submit]');if(send){send.hidden=false;send.style.display='block';send.style.visibility='visible';}" +
      "})()";
    view.evaluateJavascript(js,null);
  }

  private void applyMobileCss(WebView view) {
    try (InputStream in = getAssets().open("mobile.css")) {
      byte[] bytes = new byte[in.available()];
      int len = in.read(bytes);
      if (len <= 0) return;
      String css = new String(bytes, 0, len, StandardCharsets.UTF_8);
      String js = "(function(){" +
        "var m=document.querySelector('meta[name=viewport]');if(!m){m=document.createElement('meta');m.name='viewport';document.head.appendChild(m);}m.content='width=device-width,initial-scale=1,viewport-fit=cover';" +
        "if(location.pathname.endsWith('/chatroom.php'))document.body.classList.add('asdi-android-chat-only');" +
        "var old=document.getElementById('asdi-mobile-css');if(old)old.remove();" +
        "var st=document.createElement('style');st.id='asdi-mobile-css';st.textContent=" + JSONObject.quote(css) + ";document.head.appendChild(st);" +
        "})()";
      view.evaluateJavascript(js, null);
    } catch (Exception ignored) { }
  }


  // Hide server-rendered ticketing navigation. The server uses a shared header
  // whose menu is still present even when the WebView opens chatroom.php.
  // Remove only the known ticketing header/nav, never the chatroom panels.
  private void enforceChatOnly(WebView view) {
    String js = "(function(){" +
      "if(!location.pathname.toLowerCase().endsWith('/chatroom.php'))return;" +
      "document.body.classList.add('asdi-android-chat-only');" +
      "function hideChrome(){" +
      "document.querySelectorAll('header.top,nav.nav,nav.asdi-nav-clean,.asdi-nav-clean,.asdi-chat-taskbar,.asdi-chat-dock,footer,.site-footer').forEach(function(el){el.style.setProperty('display','none','important');el.setAttribute('aria-hidden','true');});" +
      "var room=document.getElementById('asdi-chat-workspace')||document.querySelector('.asdi-room');" +
      "if(room){var el=room.parentElement;while(el&&el!==document.body){el.style.setProperty('max-width','100%','important');el.style.setProperty('width','100%','important');el.style.setProperty('margin','0','important');el.style.setProperty('padding','0','important');el=el.parentElement;}}" +
      "}" +
      "hideChrome();" +
      "if(window.__asdiChromeObserver)window.__asdiChromeObserver.disconnect();" +
      "window.__asdiChromeObserver=new MutationObserver(hideChrome);" +
      "window.__asdiChromeObserver.observe(document.body,{childList:true,subtree:false});" +
      "})()";
    view.evaluateJavascript(js, null);
  }

  private void pollChat() {
    if(web == null || web.getUrl() == null || !web.getUrl().startsWith(base)) return;
    String js = "(function(){fetch('mobile_chat_notifications.php',{credentials:'same-origin',cache:'no-store'})" +
      ".then(r=>r.ok?r.json():null).then(j=>{if(j&&j.ok)ASDI.onChatSummary(JSON.stringify(j));}).catch(()=>{});})()";
    web.evaluateJavascript(js, null);
  }
  private void handleChatSummary(String json) {
    try {
      JSONObject result = new JSONObject(json);
      long group = result.optLong("group_latest", 0);
      long direct = result.optLong("private_latest", 0);
      if(lastGroupMessageId < 0 || lastPrivateMessageId < 0) {
        lastGroupMessageId = group; lastPrivateMessageId = direct;
      } else {
        int count = 0;
        if(group > lastGroupMessageId) count++;
        if(direct > lastPrivateMessageId) count++;
        if(count > 0) {
          unread = Math.min(99, unread + count);
          badge.setText(unread >= 99 ? "99+" : String.valueOf(unread));
          badge.setVisibility(View.VISIBLE);
          showNotification();
        }
        lastGroupMessageId = Math.max(lastGroupMessageId, group);
        lastPrivateMessageId = Math.max(lastPrivateMessageId, direct);
      }
      getPreferences(0).edit()
        .putLong("chat_group_last_" + base,lastGroupMessageId)
        .putLong("chat_private_last_" + base,lastPrivateMessageId).apply();
    } catch(Exception ignored) {}
  }

  private void pollTickets() {
    if (web == null || web.getUrl() == null || !web.getUrl().startsWith(base)) return;
    String js = "(function(){fetch('mobile_ticket_notifications.php?after=" + Math.max(0,lastTicketId) +
      "',{credentials:'same-origin',cache:'no-store'}).then(r=>r.ok?r.json():null)" +
      ".then(j=>{if(j&&Array.isArray(j.tickets))ASDI.onTickets(JSON.stringify(j));}).catch(()=>{});})()";
    web.evaluateJavascript(js, null);
  }
  private void handleTickets(String json) {
    try {
      JSONObject response = new JSONObject(json);
      long latest = response.optLong("latest",0);
      JSONArray tickets = response.optJSONArray("tickets");
      if (lastTicketId < 0) {
        lastTicketId = latest; // First run: do not alert on historical tickets.
      } else if (tickets != null) {
        for (int i=0;i<tickets.length();i++) {
          JSONObject ticket = tickets.getJSONObject(i);
          if (ticket.optLong("id") > lastTicketId) notifyTicket(ticket);
        }
        lastTicketId = Math.max(lastTicketId, latest);
      }
      getPreferences(0).edit().putLong("ticket_last_" + base,lastTicketId).apply();
    } catch(Exception ignored) { }
  }
  private void notifyTicket(JSONObject ticket) {
    if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return;
    long id=ticket.optLong("id");
    JSONObject fields=ticket.optJSONObject("fields");
    StringBuilder details=new StringBuilder();
    if(fields!=null) {
      java.util.Iterator<String> keys=fields.keys();
      while(keys.hasNext()) {
        String key=keys.next();
        if(details.length()>0)details.append("\n");
        details.append(key).append(": ").append(fields.optString(key));
      }
    }
    Intent open=new Intent(this,MainActivity.class);
    open.setAction("ticket_"+id);
    open.putExtra("open_ticket",base+"ticket_view.php?id="+id);
    open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_CLEAR_TOP);
    PendingIntent pending=PendingIntent.getActivity(this,(int)(id%Integer.MAX_VALUE),open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    Notification.Builder builder=Build.VERSION.SDK_INT>=26 ? new Notification.Builder(this,TICKET_CHANNEL) : new Notification.Builder(this);
    builder.setSmallIcon(android.R.drawable.ic_dialog_info)
      .setContentTitle(ticket.optString("title","New ASDI ticket"))
      .setContentText(fields==null?"Tap to view ticket":fields.optString("Customer","Tap to view ticket"))
      .setStyle(new Notification.BigTextStyle().bigText(details.toString()))
      .setContentIntent(pending).setAutoCancel(true);
    ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify((int)(30000+id%100000),builder.build());
  }
  private void handleIntent(Intent intent) {
    if(intent!=null && intent.hasExtra("open_ticket") && web!=null) {
      String url=intent.getStringExtra("open_ticket");
      if(url!=null && url.startsWith(base+"ticket_view.php?id=")) web.loadUrl(url);
    }
  }
  // No Android action-bar menu or tabs. The WebView is the only main screen.
  @Override public boolean onCreateOptionsMenu(Menu menu) {
    menu.add("Log out");
    return true;
  }
  @Override public boolean onOptionsItemSelected(MenuItem item) {
    if ("Log out".contentEquals(item.getTitle())) { confirmLogout(); return true; }
    return super.onOptionsItemSelected(item);
  }
  private void confirmLogout() {
    new AlertDialog.Builder(this).setTitle("Log out of ASDI Chatroom?")
      .setMessage("Your current session will end.")
      .setNegativeButton("Cancel", null)
      .setPositiveButton("Log out", (dialog, which) -> logout())
      .show();
  }
  private void logout() {
    // End the server-side PHP session first. The endpoint is installed by
    // the companion v3.34 server upgrade; never merely navigate to login.
    web.evaluateJavascript("(function(){fetch('mobile_logout.php',{method:'POST',credentials:'same-origin',cache:'no-store'})" +
      ".then(r=>{if(!r.ok)throw Error('logout failed');return r.json()})" +
      ".then(j=>{if(j&&j.ok)ASDI.onLogout('ok');else ASDI.onLogout('error')})" +
      ".catch(()=>ASDI.onLogout('error'));})()", null);
  }

  private int dp(int v) { return (int)(v * getResources().getDisplayMetrics().density + .5f); }
  private void updateMessages(long newest, int incoming) {
    if (!initialized) {
      initialized = true;
      lastSeen = Math.max(lastSeen, newest);
      getPreferences(0).edit().putLong("last_seen_" + base, lastSeen).apply();
      return;
    }
    if (newest <= lastSeen) return;
    // IDs can have gaps: count indicates new IDs, not necessarily unread messages.
    long delta = Math.max(0, incoming);
    lastSeen = newest;
    getPreferences(0).edit().putLong("last_seen_" + base, lastSeen).apply();
    if (delta == 0) return;
    unread = (int)Math.min(999, unread + Math.min(delta, 99));
    badge.setText(unread > 99 ? "99+" : String.valueOf(unread));
    badge.setVisibility(View.VISIBLE);
    showNotification();
  }
  private void showNotification() {
    if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return;
    Intent open = new Intent(this, MainActivity.class);
    open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
    PendingIntent pending = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    Notification n = new Notification.Builder(this, CHANNEL)
      .setSmallIcon(android.R.drawable.ic_dialog_email)
      .setContentTitle("ASDI Chatroom")
      .setContentText("New chat messages received")
      .setContentIntent(pending)
      .setAutoCancel(true)
      .setNumber(unread)
      .build();
    ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(2001, n);
  }
  @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); handleIntent(intent); clearBadge(); }
  private void clearBadge() {
    unread = 0;
    if (badge != null) badge.setVisibility(View.GONE);
    ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).cancel(2001);
  }
  @Override protected void onResume() { super.onResume(); active = true; }
  @Override protected void onPause() { active = false; super.onPause(); }
  @Override public void onBackPressed() {
    if (web == null) { super.onBackPressed(); return; }
    String url = web.getUrl();
    if (isLoginUrl(url)) { super.onBackPressed(); return; }
    if (!isChatUrl(url)) { web.loadUrl(chatUrl); return; }
    new AlertDialog.Builder(this).setTitle("Exit ASDI Chatroom?")
      .setNegativeButton("Stay", null).setPositiveButton("Exit", (dialog, which) -> finish()).show();
  }
  @Override protected void onDestroy() { active = false; handler.removeCallbacksAndMessages(null); if (web != null) web.destroy(); super.onDestroy(); }
}

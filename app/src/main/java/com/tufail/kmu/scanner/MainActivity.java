package com.tufail.kmu.scanner;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.util.Base64;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.webkit.WebViewAssetLoader;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * KMU Results Scanner - a simple wrapper app.
 * It shows the scanner HTML (stored inside the app, in
 * app/src/main/assets/kmu-results-scanner.html) in a full-screen WebView.
 * No sign-in, no server, no account.
 *
 * Two WebView things the scanner needs, handled here:
 * 1. Folder / file picking (onShowFileChooser). The "Choose folder" button opens
 *    the system folder picker and every PDF inside the folder is handed back to
 *    the page. The "Choose files" button opens the normal file picker
 *    (PDF files, as many at once as you want).
 * 2. Downloads. The page makes its reports (PDF, DOC, TXT, CSV, HTML) as blobs
 *    and clicks a download link. Android WebView does not save blob: downloads
 *    by itself, so a small JavaScript bridge catches them and writes the file
 *    into the phone's Downloads folder.
 *
 * To update the scanner: replace ONE file,
 * app/src/main/assets/kmu-results-scanner.html, with the new exported HTML
 * (same name, same place). Nothing else in the app has to change.
 *
 * FIX 2026-11-16b: the page is served over a real https origin from the
 * app's own files (WebViewAssetLoader), so pdf.js and Tesseract.js load
 * from inside the app and the scanner works fully offline. The library
 * files (pdf.min.js, pdf.worker.min.js, tesseract.min.js, worker.min.js,
 * the 8 core files and eng.traineddata.gz) live in app/src/main/assets/
 * next to the HTML.
 */
public class MainActivity extends Activity {

    private static final int REQ_PICK_FILES = 1001;
    private static final int REQ_PICK_FOLDER = 1002;
    private static final int MAX_FOLDER_FILES = 1000;

    private WebView webView;
    private ValueCallback<Uri[]> fileChooserCallback;

    /**
     * Injected into the page after it loads. It watches for blob downloads:
     * when the page creates a download link for a blob and clicks it, the file
     * is read and handed to the Android side (KmuBridge.saveFile) instead of
     * relying on the browser's download manager, which WebView does not do
     * for blob: links. Normal links are left alone.
     */
    private static final String BLOB_BRIDGE_JS =
            "(function(){\n" +
            "  if (window.__kmuBridgeInstalled) return;\n" +
            "  window.__kmuBridgeInstalled = true;\n" +
            "  window.__kmuBlobs = {};\n" +
            "  var realCreate = URL.createObjectURL.bind(URL);\n" +
            "  URL.createObjectURL = function(blob){\n" +
            "    var url = realCreate(blob);\n" +
            "    try { window.__kmuBlobs[url] = blob; } catch(e){}\n" +
            "    return url;\n" +
            "  };\n" +
            "  var realClick = HTMLAnchorElement.prototype.click;\n" +
            "  HTMLAnchorElement.prototype.click = function(){\n" +
            "    try {\n" +
            "      var href = this.href || '';\n" +
            "      var name = this.getAttribute('download');\n" +
            "      if (name && href.indexOf('blob:') === 0) {\n" +
            "        var blob = window.__kmuBlobs[href];\n" +
            "        if (blob) {\n" +
            "          var reader = new FileReader();\n" +
            "          reader.onload = function(){\n" +
            "            try { KmuBridge.saveFile(name, reader.result); } catch(e){}\n" +
            "          };\n" +
            "          reader.readAsDataURL(blob);\n" +
            "          return;\n" +
            "        }\n" +
            "      }\n" +
            "    } catch(e){}\n" +
            "    return realClick.call(this);\n" +
            "  };\n" +
            "})();";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        webView.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(webView);

        // FIX 2026-11-15g: keep the screen on while the app is open, so a
        // long scan is never cut off by the screen turning off.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setBuiltInZoomControls(false);

        // FIX 2026-11-16b: serve the scanner over a real https origin from
        // the app's own files (WebViewAssetLoader), so the photo reader
        // (pdf.js + Tesseract.js, bundled inside the app) can start its
        // workers and load its files — a file:// page cannot do that — and
        // the scanner works fully offline, with no internet at all.
        final WebViewAssetLoader assetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        webView.addJavascriptInterface(new DownloadBridge(), "KmuBridge");
        webView.addJavascriptInterface(new ScanBridge(), "ScanBridge");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                // Serve the app's own files (HTML + the bundled photo-reader
                // libraries) over the https origin below.
                return assetLoader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                // Keep the scanner itself inside the app; anything else opens in the browser.
                if ("https".equals(uri.getScheme()) && "appassets.androidplatform.net".equals(uri.getHost())) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (Exception ignored) {
                }
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                view.evaluateJavascript(BLOB_BRIDGE_JS, null);
            }
        });

        webView.setWebChromeClient(new ScannerChromeClient());

        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent,
                                        String contentDisposition, String mimeType,
                                        long contentLength) {
                // Fallback for normal (http/https) downloads. Blob downloads are
                // caught by the JavaScript bridge instead.
                try {
                    DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
                    req.setMimeType(mimeType);
                    String name = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimeType);
                    req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
                    req.setNotificationVisibility(
                            DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                    DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                    if (dm != null) dm.enqueue(req);
                    toast("Downloading: " + name);
                } catch (Exception e) {
                    toast("Download failed. Use the Copy button instead.");
                }
            }
        });

        webView.loadUrl("https://appassets.androidplatform.net/assets/kmu-results-scanner.html");
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (fileChooserCallback == null) return;
        ValueCallback<Uri[]> cb = fileChooserCallback;
        fileChooserCallback = null;
        if (resultCode != RESULT_OK || data == null) {
            cb.onReceiveValue(null);
            return;
        }
        if (requestCode == REQ_PICK_FILES) {
            List<Uri> uris = new ArrayList<>();
            ClipData clip = data.getClipData();
            if (clip != null) {
                for (int i = 0; i < clip.getItemCount(); i++) {
                    Uri u = clip.getItemAt(i).getUri();
                    if (u != null) uris.add(u);
                }
            } else if (data.getData() != null) {
                uris.add(data.getData());
            }
            cb.onReceiveValue(uris.toArray(new Uri[0]));
        } else if (requestCode == REQ_PICK_FOLDER) {
            Uri tree = data.getData();
            List<Uri> pdfs = new ArrayList<>();
            if (tree != null) {
                try {
                    getContentResolver().takePersistableUriPermission(
                            tree, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Exception ignored) {
                }
                collectPdfs(tree, DocumentsContract.getTreeDocumentId(tree), pdfs);
            }
            cb.onReceiveValue(pdfs.toArray(new Uri[0]));
            if (pdfs.isEmpty()) {
                toast("No PDF found in that folder.");
            } else {
                toast(pdfs.size() + " PDFs picked.");
            }
        }
    }

    /**
     * Lists every PDF under a picked folder, including subfolders.
     * Used for the "Choose folder" button of the scanner.
     */
    private void collectPdfs(Uri treeUri, String parentId, List<Uri> out) {
        if (out.size() >= MAX_FOLDER_FILES) return;
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId);
        Cursor c = null;
        try {
            c = getContentResolver().query(childrenUri, new String[]{
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
            }, null, null, null);
            if (c == null) return;
            while (c.moveToNext() && out.size() < MAX_FOLDER_FILES) {
                String id = c.getString(0);
                String name = c.getString(1);
                String mime = c.getString(2);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    collectPdfs(treeUri, id, out);
                } else if (name != null && name.toLowerCase().endsWith(".pdf")) {
                    out.add(DocumentsContract.buildDocumentUriUsingTree(treeUri, id));
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private void toastOnUi(final String msg) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                toast(msg);
            }
        });
    }

    private static String mimeTypeFor(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".pdf")) return "application/pdf";
        if (n.endsWith(".doc")) return "application/msword";
        if (n.endsWith(".txt")) return "text/plain";
        if (n.endsWith(".csv")) return "text/csv";
        if (n.endsWith(".html") || n.endsWith(".htm")) return "text/html";
        return "application/octet-stream";
    }

    /**
     * Writes a report file into the phone's Downloads folder.
     * On Android 10 and newer this uses MediaStore (no permission needed).
     * On older Android it writes to the public Downloads folder.
     */
    private String writeToDownloads(String fileName, byte[] bytes) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues v = new ContentValues();
                v.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, fileName);
                v.put(android.provider.MediaStore.Downloads.MIME_TYPE, mimeTypeFor(fileName));
                v.put(android.provider.MediaStore.Downloads.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS);
                Uri uri = getContentResolver().insert(
                        android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri == null) return null;
                try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                    if (out == null) return null;
                    out.write(bytes);
                }
                return fileName;
            } else {
                File dir = Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS);
                if (!dir.exists() && !dir.mkdirs()) return null;
                File outFile = new File(dir, fileName);
                try (FileOutputStream fos = new FileOutputStream(outFile)) {
                    fos.write(bytes);
                }
                return fileName;
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** Called from the page's JavaScript when a report download is clicked. */
    private class DownloadBridge {
        @JavascriptInterface
        public void saveFile(String fileName, String dataUrl) {
            try {
                if (fileName == null || fileName.isEmpty()) {
                    fileName = "KMU_Scan_Report.txt";
                }
                // Never allow a folder in the name.
                fileName = new File(fileName).getName();
                int comma = dataUrl == null ? -1 : dataUrl.indexOf(',');
                if (comma < 0) {
                    toastOnUi("Download failed. Use the Copy button instead.");
                    return;
                }
                byte[] bytes = Base64.decode(dataUrl.substring(comma + 1), Base64.DEFAULT);
                String saved = writeToDownloads(fileName, bytes);
                if (saved != null) {
                    toastOnUi("Saved to Downloads: " + saved);
                } else {
                    toastOnUi("Download failed. Use the Copy button instead.");
                }
            } catch (Exception e) {
                toastOnUi("Download failed. Use the Copy button instead.");
            }
        }
    }

    /**
     * Called from the page when a scan starts / finishes. Starts a small
     * foreground service (ScanService) that shows one quiet notification
     * and keeps the app alive while the scan runs, so the scan can finish
     * even if the user switches to another app or locks the phone.
     */
    private class ScanBridge {
        @JavascriptInterface
        public void startScan() {
            try {
                Intent i = new Intent(MainActivity.this, ScanService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(i);
                } else {
                    startService(i);
                }
            } catch (Exception ignored) {
            }
        }

        @JavascriptInterface
        public void stopScan() {
            try {
                stopService(new Intent(MainActivity.this, ScanService.class));
            } catch (Exception ignored) {
            }
        }
    }

    /** File / folder picker for the scanner's two pick buttons. */
    private class ScannerChromeClient extends WebChromeClient {
        @Override
        public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> callback,
                                         FileChooserParams params) {
            if (fileChooserCallback != null) {
                fileChooserCallback.onReceiveValue(null);
            }
            fileChooserCallback = callback;
            boolean pdfOnly = false;
            try {
                String[] accept = params.getAcceptTypes();
                if (accept != null) {
                    for (String a : accept) {
                        if (a != null && a.toLowerCase().contains("pdf")) pdfOnly = true;
                    }
                }
            } catch (Exception ignored) {
            }
            try {
                if (pdfOnly) {
                    // "Choose files" button: pick PDF files, as many as you want.
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("application/pdf");
                    i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    startActivityForResult(Intent.createChooser(i, "Choose PDF files"),
                            REQ_PICK_FILES);
                } else {
                    // "Choose folder" button: pick a whole folder of PDFs.
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                    startActivityForResult(i, REQ_PICK_FOLDER);
                }
                return true;
            } catch (Exception e) {
                fileChooserCallback = null;
                return false;
            }
        }
    }
}

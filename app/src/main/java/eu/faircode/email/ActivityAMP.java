package eu.faircode.email;

/*
    This file is part of FairEmail.

    FairEmail is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    FairEmail is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with FairEmail.  If not, see <http://www.gnu.org/licenses/>.

    Copyright 2018-2022 by Marcel Bokhorst (M66B)
*/

import static androidx.webkit.WebSettingsCompat.FORCE_DARK_OFF;
import static androidx.webkit.WebSettingsCompat.FORCE_DARK_ON;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;

import androidx.annotation.NonNull;
import androidx.constraintlayout.widget.Group;
import androidx.preference.PreferenceManager;
import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewFeature;

import com.google.android.material.snackbar.Snackbar;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.FileNotFoundException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class ActivityAMP extends ActivityBase {
    private WebView wvAmp;
    private ContentLoadingProgressBar pbWait;
    private Group grpReady;

    private boolean force_light = false;

    private static final List<String> ALLOWED_SCRIPT_HOSTS = Collections.unmodifiableList(Arrays.asList(
            "cdn.ampproject.org"
    ));

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (savedInstanceState != null)
            force_light = savedInstanceState.getBoolean("fair:force_light");

        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        getSupportActionBar().setSubtitle("AMP");

        View view = LayoutInflater.from(this).inflate(R.layout.activity_amp, null);
        setContentView(view);

        wvAmp = findViewById(R.id.wvAmp);
        pbWait = findViewById(R.id.pbWait);
        grpReady = findViewById(R.id.grpReady);

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        boolean overview_mode = prefs.getBoolean("overview_mode", false);
        boolean safe_browsing = prefs.getBoolean("safe_browsing", false);

        WebSettings settings = wvAmp.getSettings();
        settings.setUserAgentString(WebViewEx.getUserAgent(this, wvAmp));
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(overview_mode);

        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);

        settings.setLayoutAlgorithm(WebSettings.LayoutAlgorithm.TEXT_AUTOSIZING);

        settings.setAllowFileAccess(false);
        settings.setCacheMode(WebSettings.LOAD_CACHE_ELSE_NETWORK);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        if (WebViewEx.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE))
            WebSettingsCompat.setSafeBrowsingEnabled(settings, safe_browsing);

        setDarkMode();

        settings.setLoadsImagesAutomatically(true);
        settings.setBlockNetworkLoads(false);
        settings.setBlockNetworkImage(false);
        settings.setJavaScriptEnabled(true);

        // Initialize
        grpReady.setVisibility(View.GONE);

        load();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putBoolean("fair:force_light", force_light);
        super.onSaveInstanceState(outState);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.menu_amp, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        boolean available =
                (WebViewEx.isFeatureSupported(WebViewFeature.FORCE_DARK) &&
                        Helper.isDarkTheme(this));
        menu.findItem(R.id.menu_force_light)
                .setVisible(available)
                .getIcon().setLevel(force_light ? 1 : 0);
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int itemId = item.getItemId();
        if (itemId == R.id.menu_force_light) {
            onMenuForceLight();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void onMenuForceLight() {
        force_light = !force_light;
        invalidateOptionsMenu();
        setDarkMode();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        load();
    }

    private void setDarkMode() {
        WebSettings settings = wvAmp.getSettings();
        boolean dark = (Helper.isDarkTheme(this) && !force_light);
        if (WebViewEx.isFeatureSupported(WebViewFeature.FORCE_DARK))
            WebSettingsCompat.setForceDark(settings, dark ? FORCE_DARK_ON : FORCE_DARK_OFF);
    }

    private void load() {
        Uri uri = getIntent().getData();
        Log.i("AMP uri=" + uri);

        String subject = getIntent().getStringExtra("subject");
        if (TextUtils.isEmpty(subject))
            subject = "AMP";
        getSupportActionBar().setSubtitle(subject);

        Bundle args = new Bundle();
        args.putParcelable("uri", uri);

        new SimpleTask<String>() {
            @Override
            protected void onPreExecute(Bundle args) {
                pbWait.setVisibility(View.VISIBLE);
            }

            @Override
            protected void onPostExecute(Bundle args) {
                pbWait.setVisibility(View.GONE);
            }

            @Override
            protected String onExecute(Context context, Bundle args) throws Throwable {
                Uri uri = args.getParcelable("uri");

                if (uri == null)
                    throw new FileNotFoundException();

                if (!"content".equals(uri.getScheme()) &&
                        !Helper.hasPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE)) {
                    Log.w("AMP uri=" + uri);
                    throw new IllegalArgumentException(context.getString(R.string.title_no_stream));
                }

                String html;
                ContentResolver resolver = context.getContentResolver();
                try (InputStream is = resolver.openInputStream(uri)) {
                    html = Helper.readStream(is);
                }

                Document d = JsoupEx.parse(html);
                HtmlHelper.setViewport(d, false);

                for (Element script : d.select("script")) {
                    String src = script.attr("src");
                    Uri u = Uri.parse(src);
                    String host = (u.isHierarchical() ? u.getHost() : null);
                    if (host == null || !ALLOWED_SCRIPT_HOSTS.contains(host.toLowerCase(Locale.ROOT)))
                        script.removeAttr("src");
                }

                return d.html();
            }

            @Override
            protected void onExecuted(Bundle args, String amp) {
                wvAmp.loadDataWithBaseURL(null, amp, "text/html", StandardCharsets.UTF_8.name(), null);
                grpReady.setVisibility(View.VISIBLE);
            }

            @Override
            protected void onException(Bundle args, @NonNull Throwable ex) {
                if (ex instanceof IllegalArgumentException)
                    Snackbar.make(findViewById(android.R.id.content), ex.getMessage(), Snackbar.LENGTH_LONG)
                            .setGestureInsetBottomIgnored(true).show();
                else
                    Log.unexpectedError(getSupportFragmentManager(), ex, false);
            }
        }.execute(this, args, "amp:decode");
    }
}
package com.texmod;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.util.Base64;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class MainActivity extends Activity {
    EditText token, repo;
    ImageView preview;
    TextView status;
    Uri picked;
    SharedPreferences sp;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        sp = getSharedPreferences("s", MODE_PRIVATE);
        int p = (int) (16 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(p, p * 2, p, p);

        repo = new EditText(this);
        repo.setHint("GitHub repo (owner/name)");
        repo.setText(sp.getString("repo", ""));
        token = new EditText(this);
        token.setHint("GitHub token");
        token.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        token.setText(sp.getString("token", ""));

        Button choose = new Button(this);
        choose.setText("Choose image");
        preview = new ImageView(this);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 1f));
        Button go = new Button(this);
        go.setText("Make funny texture game");
        status = new TextView(this);
        status.setPadding(0, p, 0, 0);

        root.addView(repo);
        root.addView(token);
        root.addView(choose);
        root.addView(preview);
        root.addView(go);
        root.addView(status);
        setContentView(root);

        choose.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setType("image/*");
            startActivityForResult(i, 1);
        });
        go.setOnClickListener(v -> run());
    }

    @Override
    protected void onActivityResult(int req, int res, Intent d) {
        if (req == 1 && res == RESULT_OK && d != null) {
            picked = d.getData();
            preview.setImageURI(picked);
        }
    }

    void say(String s) {
        runOnUiThread(() -> status.setText(s));
    }

    void run() {
        if (picked == null) { say("Pick an image first"); return; }
        final String r = repo.getText().toString().trim();
        final String t = token.getText().toString().trim();
        sp.edit().putString("repo", r).putString("token", t).apply();
        new Thread(() -> {
            try {
                say("Preparing image...");
                byte[] png = shrink(picked);
                String api = "https://api.github.com/repos/" + r;
                String branch = new JSONObject(call("GET", api, t, null)).getString("default_branch");
                String sha = null;
                try {
                    sha = new JSONObject(call("GET", api + "/contents/input/image?ref=" + branch, t, null)).getString("sha");
                } catch (Exception e) { /* file doesn't exist yet */ }
                JSONObject put = new JSONObject()
                        .put("message", "new texture")
                        .put("branch", branch)
                        .put("content", Base64.encodeToString(png, Base64.NO_WRAP));
                if (sha != null) put.put("sha", sha);
                say("Uploading image...");
                call("PUT", api + "/contents/input/image", t, put.toString());
                say("Starting build...");
                call("POST", api + "/actions/workflows/retexture.yml/dispatches", t,
                        new JSONObject().put("ref", branch).toString());
                say("Build started. When it finishes (a few minutes), download \"modded-game\" from the Actions page.");
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/" + r + "/actions")));
            } catch (Exception e) {
                say("Error: " + e.getMessage());
            }
        }).start();
    }

    byte[] shrink(Uri u) throws IOException {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        try (InputStream in = getContentResolver().openInputStream(u)) {
            BitmapFactory.decodeStream(in, null, o);
        }
        int s = 1;
        while (Math.max(o.outWidth, o.outHeight) / s > 1024) s *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = s;
        Bitmap bm;
        try (InputStream in = getContentResolver().openInputStream(u)) {
            bm = BitmapFactory.decodeStream(in, null, o2);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bm.compress(Bitmap.CompressFormat.PNG, 100, out);
        return out.toByteArray();
    }

    static String call(String method, String url, String tok, String body) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setRequestProperty("Authorization", "Bearer " + tok);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            try (OutputStream o = c.getOutputStream()) { o.write(body.getBytes("UTF-8")); }
        }
        int code = c.getResponseCode();
        InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        if (in != null) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        }
        String s = bo.toString("UTF-8");
        if (code >= 400) throw new IOException(code + " " + s);
        return s;
    }
}

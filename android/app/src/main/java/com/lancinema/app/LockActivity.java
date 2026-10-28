package com.lancinema.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.*;

public final class LockActivity extends Activity {
  CinemaApp app;
  TextView title, hint;
  EditText password, confirmation;
  Button submit;
  boolean setting, managing;

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    app = (CinemaApp) getApplication();
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    managing = getIntent().getBooleanExtra("manage", false);
    setting = !AppLock.enabled(this);
    LinearLayout layout = new LinearLayout(this);
    layout.setOrientation(LinearLayout.VERTICAL);
    layout.setGravity(Gravity.CENTER_VERTICAL);
    layout.setPadding(36, 30, 36, 30);
    layout.setBackgroundColor(Color.rgb(16, 23, 20));
    TextView mark = new TextView(this);
    mark.setText("◧  LAN CINEMA");
    mark.setTextColor(Color.rgb(201, 245, 145));
    mark.setTextSize(14);
    layout.addView(mark);
    title = new TextView(this);
    title.setTextSize(29);
    title.setTextColor(Color.WHITE);
    title.setPadding(0, 30, 0, 15);
    layout.addView(title);
    hint = new TextView(this);
    hint.setTextColor(Color.rgb(142, 170, 151));
    hint.setTextSize(14);
    hint.setPadding(0, 0, 0, 20);
    layout.addView(hint);
    password = field("解锁密码");
    confirmation = field("再次输入密码");
    layout.addView(password);
    layout.addView(confirmation);
    submit = new Button(this);
    submit.setTextColor(Color.rgb(16, 23, 20));
    submit.setBackgroundTintList(
        android.content.res.ColorStateList.valueOf(Color.rgb(201, 245, 145)));
    layout.addView(submit);
    submit.setOnClickListener(v -> submit());
    Button cancel = new Button(this);
    cancel.setText(managing ? "取消" : "退出 App");
    cancel.setOnClickListener(
        v -> {
          if (managing) finish();
          else finishAffinity();
        });
    layout.addView(cancel);
    setContentView(layout);
    update();
  }

  EditText field(String placeholder) {
    EditText input = new EditText(this);
    input.setSingleLine();
    input.setHint(placeholder);
    input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
    input.setTextColor(Color.WHITE);
    input.setHintTextColor(Color.GRAY);
    input.setPadding(12, 18, 12, 18);
    return input;
  }

  void update() {
    title.setText(setting ? "设置解锁密码" : "欢迎回来。");
    hint.setText(setting ? "至少 4 个字符，请记住密码。忘记密码需要清除 App 数据，离线下载会丢失。" : "输入密码，打开你的局域影库。");
    confirmation.setVisibility(setting ? android.view.View.VISIBLE : android.view.View.GONE);
    submit.setText(setting ? "保存密码" : "解锁");
    password.setText("");
    confirmation.setText("");
  }

  void submit() {
    String value = password.getText().toString();
    android.content.SharedPreferences prefs = getSharedPreferences("lock", 0);
    long wait = prefs.getLong("until", 0) - System.currentTimeMillis();
    if (wait > 0) {
      hint.setText("尝试过多，请 " + (wait / 1000 + 1) + " 秒后重试");
      return;
    }
    if (setting
        && (value.length() < 4
            || value.length() > 128
            || !value.equals(confirmation.getText().toString()))) {
      hint.setText("请使用 4–128 个字符，并确保两次密码一致");
      return;
    }
    submit.setEnabled(false);
    password.setEnabled(false);
    confirmation.setEnabled(false);
    app.io.execute(
        () -> {
          boolean success = false;
          try {
            if (setting) {
              AppLock.set(this, value);
              success = true;
            } else success = AppLock.verify(this, value);
          } catch (Exception ignored) {
          }
          boolean valid = success;
          runOnUiThread(
              () -> {
                submit.setEnabled(true);
                password.setEnabled(true);
                confirmation.setEnabled(true);
                if (!valid) {
                  int failures = prefs.getInt("failures", 0) + 1;
                  prefs
                      .edit()
                      .putInt("failures", failures)
                      .putLong("until", failures >= 5 ? System.currentTimeMillis() + 30000 : 0)
                      .apply();
                  hint.setText(failures >= 5 ? "连续输错，请 30 秒后再试" : "密码不正确，请重试");
                  password.setText("");
                  return;
                }
                prefs.edit().putInt("failures", 0).putLong("until", 0).apply();
                app.unlocked = true;
                if (managing && !setting)
                  new AlertDialog.Builder(this)
                      .setTitle("密码设置")
                      .setItems(
                          new String[] {"修改密码", "关闭密码解锁"},
                          (d, which) -> {
                            if (which == 0) {
                              setting = true;
                              update();
                            } else {
                              prefs.edit().remove("hash").apply();
                              finish();
                            }
                          })
                      .setOnCancelListener(d -> finish())
                      .show();
                else finish();
              });
        });
  }

  @Override
  public void onBackPressed() {
    if (managing) super.onBackPressed();
    else finishAffinity();
  }
}

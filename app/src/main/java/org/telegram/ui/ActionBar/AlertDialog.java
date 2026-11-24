package org.telegram.ui.ActionBar;

import android.content.Context;
import android.content.DialogInterface;

public class AlertDialog {
    private final androidx.appcompat.app.AlertDialog dialog;

    private AlertDialog(androidx.appcompat.app.AlertDialog dialog) {
        this.dialog = dialog;
    }

    public void show() {
        dialog.show();
    }

    public void dismiss() {
        dialog.dismiss();
    }

    public void setCanceledOnTouchOutside(boolean cancel) {
        dialog.setCanceledOnTouchOutside(cancel);
    }

    public void setOnCancelListener(DialogInterface.OnCancelListener listener) {
        dialog.setOnCancelListener(listener);
    }

    public static class Builder {
        private final androidx.appcompat.app.AlertDialog.Builder inner;

        public Builder(Context context) {
            this.inner = new androidx.appcompat.app.AlertDialog.Builder(context);
        }

        public Builder setTitle(CharSequence title) {
            inner.setTitle(title);
            return this;
        }

        public Builder setMessage(CharSequence message) {
            inner.setMessage(message);
            return this;
        }

        public Builder setItems(CharSequence[] items, DialogInterface.OnClickListener listener) {
            inner.setItems(items, listener);
            return this;
        }

        public Builder setPositiveButton(CharSequence text, DialogInterface.OnClickListener listener) {
            inner.setPositiveButton(text, listener);
            return this;
        }

        public Builder setNegativeButton(CharSequence text, DialogInterface.OnClickListener listener) {
            inner.setNegativeButton(text, listener);
            return this;
        }

        public AlertDialog create() {
            return new AlertDialog(inner.create());
        }

        public AlertDialog show() {
            AlertDialog ad = create();
            ad.show();
            return ad;
        }
    }
}

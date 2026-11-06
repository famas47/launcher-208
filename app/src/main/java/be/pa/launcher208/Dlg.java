package be.pa.launcher208;

import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.DialogInterface;
import android.text.InputType;
import android.widget.DatePicker;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TimePicker;

import java.util.Calendar;

/** Petites fenêtres de saisie (chiffre, date, texte, heure). */
final class Dlg {
    interface LongCb { void on(long v); }
    interface StrCb { void on(String v); }

    static void number(final HomeActivity a, String title, String msg, long current, final LongCb cb) {
        final EditText e = new EditText(a);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        if (current > 0) e.setText(String.valueOf(current));
        e.setSelectAllOnFocus(true);
        FrameLayout box = new FrameLayout(a);
        U.pad(box, 24, 8, 24, 0);
        box.addView(e);
        a.dialog().setTitle(title).setMessage(msg).setView(box)
                .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        String s = e.getText().toString().replaceAll("[^0-9]", "");
                        if (s.isEmpty()) return;
                        try { cb.on(Long.parseLong(s)); } catch (NumberFormatException ignored) { }
                    }
                })
                .setNegativeButton("Annuler", null).show();
    }

    static void text(final HomeActivity a, String title, String current, final StrCb cb) {
        final EditText e = new EditText(a);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        e.setText(current);
        FrameLayout box = new FrameLayout(a);
        U.pad(box, 24, 8, 24, 0);
        box.addView(e);
        a.dialog().setTitle(title).setView(box)
                .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { cb.on(e.getText().toString().trim()); }
                })
                .setNegativeButton("Annuler", null).show();
    }

    static void date(HomeActivity a, long current, final LongCb cb) {
        Calendar c = Fmt.cal(current > 0 ? current : Clock.now());
        int theme = Pal.night ? android.R.style.Theme_DeviceDefault_Dialog_Alert : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert;
        new DatePickerDialog(a, theme, new DatePickerDialog.OnDateSetListener() {
            @Override public void onDateSet(DatePicker v, int y, int m, int d) {
                Calendar r = Calendar.getInstance(Fmt.TZ, Fmt.FR);
                r.clear();
                r.set(y, m, d, 12, 0, 0);
                cb.on(r.getTimeInMillis());
            }
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show();
    }

    static void time(HomeActivity a, int minutes, final LongCb cb) {
        int theme = Pal.night ? android.R.style.Theme_DeviceDefault_Dialog_Alert : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert;
        new TimePickerDialog(a, theme, new TimePickerDialog.OnTimeSetListener() {
            @Override public void onTimeSet(TimePicker v, int h, int m) { cb.on(h * 60L + m); }
        }, minutes / 60, minutes % 60, true).show();
    }
}

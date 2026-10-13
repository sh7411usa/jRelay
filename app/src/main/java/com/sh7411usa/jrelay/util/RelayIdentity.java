package com.sh7411usa.jrelay.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;

import com.sh7411usa.jrelay.sms.PhoneNumberUtils;

public final class RelayIdentity {
    private static final String TAG = "RelayIdentity";
    private static final String PREFS_NAME = "relay_identity";
    private static final String KEY_OWN_NUMBER = "own_number_e164";
    private static final String KEY_PENDING_NUMBER = "pending_own_number_e164";
    private static final String KEY_PENDING_SIGNATURE = "pending_own_number_signature";
    static final int OBSERVE_IGNORE = 0;
    static final int OBSERVE_PEND = 1;
    static final int OBSERVE_PROMOTE = 2;

    private RelayIdentity() {
    }

    /** The SIM wins whenever it reports a number (SIM swap); the stored value is only a fallback. */
    public static String ownNumber(Context context) {
        try {
            SharedPreferences sp = prefs(context);
            String stored = sp.getString(KEY_OWN_NUMBER, null);
            String fromSim = readLine1Number(context);
            if (TextUtils.isEmpty(fromSim)) return TextUtils.isEmpty(stored) ? null : stored;
            if (!fromSim.equals(stored)) {
                sp.edit().putString(KEY_OWN_NUMBER, fromSim).apply();
                Log.i(TAG, "Own number from the SIM is now " + PhoneNumberUtils.mask(fromSim)
                        + (TextUtils.isEmpty(stored) ? "" : ", was " + PhoneNumberUtils.mask(stored)));
            }
            return fromSim;
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not determine the relay's own number", e);
            return null;
        }
    }

    /** Stores a candidate only after an agreeing observation from a DIFFERENT thread; MMS never overwrites a stored number. */
    public static void observeThreadCandidate(Context context, String candidate, String signature) {
        String normalized = PhoneNumberUtils.normalize(candidate);
        if (TextUtils.isEmpty(normalized) || TextUtils.isEmpty(signature)) return;
        String stored = ownNumber(context);
        if (!TextUtils.isEmpty(stored)) {
            if (!stored.equals(normalized)) {
                Log.w(TAG, "Thread's extra participant " + PhoneNumberUtils.mask(normalized)
                        + " differs from the stored own number " + PhoneNumberUtils.mask(stored) + "; keeping the stored one");
            }
            return;
        }
        SharedPreferences sp = prefs(context);
        int decision = decideObservation(sp.getString(KEY_PENDING_NUMBER, null),
                sp.getString(KEY_PENDING_SIGNATURE, null), normalized, signature);
        if (decision == OBSERVE_PROMOTE) {
            sp.edit().putString(KEY_OWN_NUMBER, normalized)
                    .remove(KEY_PENDING_NUMBER).remove(KEY_PENDING_SIGNATURE).apply();
            Log.i(TAG, "Own number learned from two group threads: " + PhoneNumberUtils.mask(normalized));
        } else if (decision == OBSERVE_PEND) {
            sp.edit().putString(KEY_PENDING_NUMBER, normalized).putString(KEY_PENDING_SIGNATURE, signature).apply();
            Log.i(TAG, "Possible own number " + PhoneNumberUtils.mask(normalized) + " seen in one group thread; waiting for another");
        }
    }

    static int decideObservation(String pendingNumber, String pendingSignature, String candidate, String signature) {
        if (pendingNumber == null || !pendingNumber.equals(candidate)) return OBSERVE_PEND;
        return signature.equals(pendingSignature) ? OBSERVE_IGNORE : OBSERVE_PROMOTE;
    }

    public static boolean isOwnNumber(Context context, String e164) {
        String own = ownNumber(context);
        return own != null && own.equals(PhoneNumberUtils.normalize(e164));
    }

    private static String readLine1Number(Context context) {
        try {
            TelephonyManager tm = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
            if (tm == null) return null;
            @SuppressWarnings({"MissingPermission", "HardwareIds"})
            String raw = tm.getLine1Number();
            return TextUtils.isEmpty(raw) ? null : PhoneNumberUtils.normalize(raw);
        } catch (SecurityException e) {
            return null;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}

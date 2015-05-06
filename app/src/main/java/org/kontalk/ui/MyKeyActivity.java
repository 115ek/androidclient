/*
 * Kontalk Android client
 * Copyright (C) 2015 Kontalk Devteam <devteam@kontalk.org>

 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.

 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.

 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.kontalk.ui;

import java.util.HashMap;
import java.util.Map;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.support.v7.app.ActionBar;
import android.support.v7.app.ActionBarActivity;
import android.util.Log;
import android.view.MenuItem;
import android.widget.ImageView;
import android.widget.TextView;

import org.kontalk.Kontalk;
import org.kontalk.R;
import org.kontalk.crypto.PGP;
import org.kontalk.crypto.PersonalKey;


/**
 * My key activity.
 * @author Daniele Ricci
 */
public class MyKeyActivity extends ActionBarActivity {
    private static final String TAG = Kontalk.TAG;

    private TextView mTextName;
    private TextView mTextFingerprint;
    private ImageView mQRCode;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.mykey_screen);

        setupActivity();

        mTextName = (TextView) findViewById(R.id.name);
        mTextFingerprint = (TextView) findViewById(R.id.fingerprint);
        mQRCode = (ImageView) findViewById(R.id.qrcode);
    }

    @Override
    protected void onStart() {
        super.onStart();
        // load personal key
        PersonalKey key;
        try {
            key = Kontalk.get(this).getPersonalKey();
        }
        catch (Exception e) {
            // TODO handle errors
            Log.w(TAG, "unable to load personal key");
            return;
        }

        // TODO network
        mTextName.setText(key.getUserId(null));

        String fingerprint = PGP.formatFingerprint(key.getFingerprint());
        mTextFingerprint.setText(fingerprint.replaceFirst("  ", "\n"));

        try {
            Bitmap qrCode = getQRCodeBitmap(fingerprint);
            mQRCode.setImageBitmap(qrCode);
        }
        catch (WriterException e) {
            // TODO handle errors
            Log.w(TAG, "unable to generate fingerprint QR code");
        }
    }

    private void setupActivity() {
        ActionBar bar = getSupportActionBar();
        bar.setDisplayHomeAsUpEnabled(true);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home:
                finish();
                return true;
        }

        return super.onOptionsItemSelected(item);
    }

    private Bitmap getQRCodeBitmap(String text) throws WriterException {
        QRCodeWriter writer = new QRCodeWriter();
        Map<EncodeHintType, Object> hints = new HashMap<>();
        hints.put(EncodeHintType.MARGIN, 2);
        BitMatrix matrix = writer.encode(text, BarcodeFormat.QR_CODE, 600, 600, hints);
        return toBitmap(matrix);
    }

    /**
     * Writes the given Matrix on a new Bitmap object.
     * http://codeisland.org/2013/generating-qr-codes-with-zxing/
     * @param matrix the matrix to write.
     * @return the new {@link Bitmap}-object.
     */
    public static Bitmap toBitmap(BitMatrix matrix){
        int height = matrix.getHeight();
        int width = matrix.getWidth();
        Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);
        for (int x = 0; x < width; x++){
            for (int y = 0; y < height; y++){
                bmp.setPixel(x, y, matrix.get(x,y) ? Color.BLACK : Color.WHITE);
            }
        }
        return bmp;
    }

}

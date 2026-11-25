package com.limelight.utils;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;

import com.limelight.Game;
import com.limelight.computers.ComputerManagerService;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvApp;

import java.io.IOException;
import java.security.cert.CertificateEncodingException;

/**
 * Helper for resolving a computer's active address and constructing the immersive
 * VR streaming launch intent ([com.limelight.GameXR]).
 */
public class ServerHelper {
    public static ComputerDetails.AddressTuple getCurrentAddressFromComputer(ComputerDetails computer) throws IOException {
        if (computer.activeAddress == null) {
            throw new IOException("No active address for " + computer.name);
        }
        return computer.activeAddress;
    }

    public static Intent createVrStartIntent(Activity parent, NvApp app, ComputerDetails computer,
                                             ComputerManagerService.ComputerManagerBinder managerBinder) {
        Intent intent = new Intent(parent, com.limelight.GameXR.class);
        intent.setComponent(new ComponentName(parent, "com.limelight.GameXR"));
        intent.addCategory("com.oculus.intent.category.VR");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra(Game.EXTRA_HOST, computer.activeAddress.address);
        intent.putExtra(Game.EXTRA_PORT, computer.activeAddress.port);
        intent.putExtra(Game.EXTRA_HTTPS_PORT, computer.httpsPort);
        intent.putExtra(Game.EXTRA_APP_NAME, app.getAppName());
        intent.putExtra(Game.EXTRA_APP_ID, app.getAppId());
        intent.putExtra(Game.EXTRA_APP_HDR, app.isHdrSupported());
        intent.putExtra(Game.EXTRA_UNIQUEID, managerBinder.getUniqueId());
        intent.putExtra(Game.EXTRA_PC_UUID, computer.uuid);
        intent.putExtra(Game.EXTRA_PC_NAME, computer.name);
        intent.putExtra(Game.EXTRA_RETURN_TO_PC_VIEW, true);
        try {
            if (computer.serverCert != null) {
                intent.putExtra(Game.EXTRA_SERVER_CERT, computer.serverCert.getEncoded());
            }
        } catch (CertificateEncodingException e) {
            e.printStackTrace();
        }
        return intent;
    }

    /** Starts an immersive VR streaming session and closes the 2D panel behind it. */
    public static void doStartVrStream(Activity parent, NvApp app, ComputerDetails computer,
                                       ComputerManagerService.ComputerManagerBinder managerBinder) {
        if (computer.state == ComputerDetails.State.OFFLINE || computer.activeAddress == null) {
            return;
        }
        Intent intent = createVrStartIntent(parent, app, computer, managerBinder);
        parent.startActivity(intent);
        parent.finishAffinity();
    }
}

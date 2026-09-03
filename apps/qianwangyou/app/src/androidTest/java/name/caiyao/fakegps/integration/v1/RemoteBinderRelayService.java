package name.caiyao.fakegps.integration.v1;

import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;

/**
 * Framework-only, test-APK Binder relay. Keeping contract and Kotlin types out of this process is
 * intentional: instrumentation can load the tested APK's classes, but an ordinary component of
 * the test APK cannot assume those classes are on its own class path.
 *
 * <p>All requests begin with {@link #DESCRIPTOR}. STATUS has no arguments and returns, after the
 * normal exception header, relay UID, relay PID, readiness (1/0), and target package. Both forward
 * operations return an exception header, transact's handled bit (1/0), and raw reply bytes. A
 * service forward takes a transaction code and raw request bytes; a supplied-Binder forward takes
 * a strong Binder first, then those same arguments. Forwarding is always synchronous.
 *
 * <p>The destination service is not caller-selectable. The package is this test APK's package
 * minus its final {@code .test}, and the service class is frozen below. Only that tested QWY
 * application's actual UID may call the relay, including when supplying a test Binder to inspect
 * identity on the receiving thread. No caller UID is accepted from request data.
 */
public final class RemoteBinderRelayService extends Service {
    public static final String DESCRIPTOR =
            "name.caiyao.fakegps.integration.v1.test.RemoteBinderRelay";
    public static final int TRANSACTION_STATUS = IBinder.FIRST_CALL_TRANSACTION;
    public static final int TRANSACTION_FORWARD_SERVICE = IBinder.FIRST_CALL_TRANSACTION + 1;
    public static final int TRANSACTION_FORWARD_BINDER = IBinder.FIRST_CALL_TRANSACTION + 2;

    private static final String PROVIDER_SERVICE_CLASS =
            "name.caiyao.fakegps.integration.v1.EnvironmentControlService";
    private static final String TEST_PACKAGE_SUFFIX = ".test";

    private volatile IBinder providerBinder;
    private String targetPackage;
    private int expectedCallerUid;
    private boolean providerBound;

    private final ServiceConnection providerConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            providerBinder = service;
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            providerBinder = null;
        }

        @Override
        public void onBindingDied(ComponentName name) {
            providerBinder = null;
        }

        @Override
        public void onNullBinding(ComponentName name) {
            providerBinder = null;
        }
    };

    private final Binder relayBinder = new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            enforceTestedApplicationCaller();
            if ((flags & IBinder.FLAG_ONEWAY) != 0 || reply == null) {
                throw new IllegalArgumentException("relay accepts synchronous transactions only");
            }
            if (code == INTERFACE_TRANSACTION) {
                reply.writeString(DESCRIPTOR);
                return true;
            }
            data.enforceInterface(DESCRIPTOR);
            switch (code) {
                case TRANSACTION_STATUS:
                    enforceNoTrailingData(data);
                    reply.writeNoException();
                    reply.writeInt(Process.myUid());
                    reply.writeInt(Process.myPid());
                    reply.writeInt(providerBinder != null ? 1 : 0);
                    reply.writeString(targetPackage);
                    return true;
                case TRANSACTION_FORWARD_SERVICE:
                    IBinder target = providerBinder;
                    if (target == null) {
                        throw new IllegalStateException("production QWY service is not connected");
                    }
                    return forward(target, data, reply);
                case TRANSACTION_FORWARD_BINDER:
                    IBinder supplied = data.readStrongBinder();
                    if (supplied == null) {
                        throw new IllegalArgumentException("test Binder must be non-null");
                    }
                    return forward(supplied, data, reply);
                default:
                    return super.onTransact(code, data, reply, flags);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        String ownPackage = getPackageName();
        if (!ownPackage.endsWith(TEST_PACKAGE_SUFFIX)) {
            throw new IllegalStateException("relay must run in the separately installed test APK");
        }
        targetPackage = ownPackage.substring(0, ownPackage.length() - TEST_PACKAGE_SUFFIX.length());
        if (!targetPackage.equals("name.caiyao.fakegps.bench")
                && !targetPackage.equals("name.caiyao.fakegps.codexbench")) {
            throw new IllegalStateException("relay only supports the isolated QWY debug variants");
        }
        try {
            expectedCallerUid = getPackageManager().getApplicationInfo(targetPackage, 0).uid;
        } catch (PackageManager.NameNotFoundException e) {
            throw new IllegalStateException("tested QWY package is not installed", e);
        }
        if (expectedCallerUid == Process.myUid()) {
            throw new IllegalStateException("relay and tested QWY must have distinct Android UIDs");
        }
        Intent providerIntent = new Intent().setComponent(
                new ComponentName(targetPackage, PROVIDER_SERVICE_CLASS));
        providerBound = bindService(providerIntent, providerConnection, Context.BIND_AUTO_CREATE);
        if (!providerBound) {
            throw new IllegalStateException("could not bind the fixed production QWY service");
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return relayBinder;
    }

    @Override
    public void onDestroy() {
        providerBinder = null;
        if (providerBound) {
            unbindService(providerConnection);
            providerBound = false;
        }
        super.onDestroy();
    }

    private void enforceTestedApplicationCaller() {
        if (Binder.getCallingUid() != expectedCallerUid) {
            throw new SecurityException("only the tested QWY application's UID may use this relay");
        }
    }

    private static void enforceNoTrailingData(Parcel data) {
        if (data.dataAvail() != 0) {
            throw new IllegalArgumentException("unexpected trailing relay request data");
        }
    }

    private static boolean forward(IBinder target, Parcel data, Parcel reply)
            throws RemoteException {
        int targetCode = data.readInt();
        byte[] requestBytes = data.createByteArray();
        enforceNoTrailingData(data);
        if (targetCode < IBinder.FIRST_CALL_TRANSACTION
                || targetCode > IBinder.LAST_CALL_TRANSACTION || requestBytes == null) {
            throw new IllegalArgumentException("invalid raw Binder request");
        }
        Parcel request = Parcel.obtain();
        Parcel response = Parcel.obtain();
        try {
            request.unmarshall(requestBytes, 0, requestBytes.length);
            request.setDataPosition(0);
            // Do not deserialize or reinterpret the contract reply. The generated proxy in the
            // instrumented QWY process is the owner of that schema and its exception header.
            boolean handled = target.transact(targetCode, request, response, 0);
            reply.writeNoException();
            reply.writeInt(handled ? 1 : 0);
            reply.writeByteArray(response.marshall());
            return true;
        } finally {
            response.recycle();
            request.recycle();
        }
    }
}

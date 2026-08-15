/*
 * This file is auto-generated.  DO NOT MODIFY.
 * Using: /Users/terry/Library/Android/sdk/build-tools/36.0.0/aidl -p/Users/terry/Library/Android/sdk/platforms/android-35/framework.aidl -o/Users/terry/Desktop/coding/fakexxx-issue6-acceptance/acceptance/build/contract-environment-control-v1/generated/aidl_source_output_dir/debug/out -I/Users/terry/Desktop/coding/fakexxx-issue6-acceptance/contracts/environment-control-v1/src/main/aidl -I/Users/terry/Desktop/coding/fakexxx-issue6-acceptance/contracts/environment-control-v1/src/debug/aidl -d/var/folders/hj/blv37f392c722542z06qry0m0000gn/T/aidl7175123754454385302.d /Users/terry/Desktop/coding/fakexxx-issue6-acceptance/contracts/environment-control-v1/src/main/aidl/io/github/terryyyc/fakexxx/contract/v1/IEnvironmentControlV1.aidl
 *
 * DO NOT CHECK THIS FILE INTO A CODE TREE (e.g. git, etc..).
 * ALWAYS GENERATE THIS FILE FROM UPDATED AIDL COMPILER
 * AS A BUILD INTERMEDIATE ONLY. THIS IS NOT SOURCE CODE.
 */
package io.github.terryyyc.fakexxx.contract.v1;
public interface IEnvironmentControlV1 extends android.os.IInterface
{
  /** Default implementation for IEnvironmentControlV1. */
  public static class Default implements io.github.terryyyc.fakexxx.contract.v1.IEnvironmentControlV1
  {
    @Override public io.github.terryyyc.fakexxx.contract.v1.EnvironmentControlResultV1 discover() throws android.os.RemoteException
    {
      return null;
    }
    @Override public io.github.terryyyc.fakexxx.contract.v1.EnvironmentControlResultV1 preflight(io.github.terryyyc.fakexxx.contract.v1.PreflightRequestV1 request) throws android.os.RemoteException
    {
      return null;
    }
    @Override public io.github.terryyyc.fakexxx.contract.v1.EnvironmentControlResultV1 apply(io.github.terryyyc.fakexxx.contract.v1.ApplyRequestV1 request) throws android.os.RemoteException
    {
      return null;
    }
    @Override public io.github.terryyyc.fakexxx.contract.v1.EnvironmentControlResultV1 observe(io.github.terryyyc.fakexxx.contract.v1.ObserveRequestV1 request) throws android.os.RemoteException
    {
      return null;
    }
    @Override public io.github.terryyyc.fakexxx.contract.v1.EnvironmentControlResultV1 release(io.github.terryyyc.fakexxx.contract.v1.ReleaseRequestV1 request) throws android.os.RemoteException
    {
      return null;
    }
    // Complete the current schedule item and advance to the next (§6.7.3).
    //
    // The provider is the sole executor of the advance because it owns the
    // schedule order; Auto is the sole judge of quota because it owns the
    // ledger. This method is the single seam between those two ownerships, and
    // it is a compare-and-advance on THREE preconditions: the request's
    // expectedScheduleId, expectedCurrentItemId and expectedScheduleVersion, so a
    // caller that lost the race is rejected rather than silently advancing someone
    // else's item. expectedScheduleId is checked FIRST (spec v1.72, §6.7.4b step
    // 4): two schedules may reuse the same (itemId, scheduleVersion), so without
    // the identity leg the compare passes on the WRONG schedule and the advance is
    // genuinely committed there. Its value must come from the projection group
    // captured when the attempt was opened -- never re-read just before the call.
    @Override public io.github.terryyyc.fakexxx.contract.v1.EnvironmentControlResultV1 completeAndAdvance(io.github.terryyyc.fakexxx.contract.v1.CompleteAndAdvanceRequestV1 request) throws android.os.RemoteException
    {
      return null;
    }
    @Override
    public android.os.IBinder asBinder() {
      return null;
    }
  }
  /** Local-side IPC implementation stub class. */
  public static abstract class Stub extends android.os.Binder implements io.github.terryyyc.fakexxx.contract.v1.IEnvironmentControlV1
  {
    /** Construct the stub and attach it to the interface. */
    @SuppressWarnings("this-escape")
    public Stub()
    {
      this.attachInterface(this, DESCRIPTOR);
    }
    /**
     * Cast an IBinder object into an io.github.terryyyc.fakexxx.contract.v1.IEnvironmentControlV1 interface,
     * generating a proxy if needed.
     */
    public static io.github.terryyyc.fakexxx.contract.v1.IEnvironmentControlV1 asInterface(android.os.IBinder obj)
    {
      if ((obj==null)) {
        return null;
      }
      android.os.IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
      if (((iin!=null)&&(iin instanceof io.github.terryyyc.fakexxx.contract.v1.IEnvironmentControlV1))) {
        return ((io.github.terryyyc.fakexxx.contract.v1.IEnvironmentControlV1)iin);
      }
      return new io.github.terryyyc.fakexxx.contract.v1.IEnvironmentControlV1.Stub.Proxy(obj);
    }
    @Override public android.os.IBinder asBinder()
    {
      return this;
    }
    @Override public boolean onTransact(int code, android.os.Parcel data, android.os.Parcel reply, int flags) throws android.os.RemoteException
    {
      java.lang.String descriptor = DESCRIPTOR;
      if (code >= android.os.IBinder.FIRST_CALL_TRANSACTION && code <= android.os.IBinder.LAST_CALL_TRANSACTION) {
        data.enforceInterface(descriptor);
      }
      if (code == INTERFACE_TRANSACTION) {
        reply.writeString(descriptor);
        return true;
      }
      switch (code)
      {
        case TRANSACTION_discover:
        {
          io.github.terryyyc.fakexxx.contract.v1.EnvironmentControlResultV1 _result = this.discover();
          reply.writeNoException();
          _Parcel.writeTypedObject(reply, _result, android.os.Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
          break;
        }
        case TRANSACTION_preflight:
        {
          io.github.terryyyc.fakexxx.contract.v1.PreflightRequestV1 _arg0;
          _arg0 = _Parcel.readTypedObject(data, io.github.terryyyc.fakexxx.contract.v1.PreflightRequestV1.CREATOR);
          io.github.terryyyc.fakexxx.contract.v1.EnvironmentControlResultV1 _result = this.preflight(_arg0);
          reply.writeNoException();
          _Parcel.writeTypedObject(reply, _result, android.os.Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
          break;
        }
        case TRANSACTION_apply:
        {
          io.github.terryyyc.fakexxx.contract.v1.ApplyRequestV1 _arg0;
          _arg0 = _Parcel.readTypedObject(data, io.github.terryyyc.fakexxx.contract.v1.ApplyRequestV1.CREATOR);
          io.github.terryyyc.fakexxx.contract.v1.EnvironmentControlResultV1 _result = this.apply(_arg0);
          reply.writeNoException();
          _Parcel.writeTypedObject(reply, _result, android.os.Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
          break;
        }
        case TRANSACTION_observe:
        {
          io.github.terryyyc.fakexxx.contract.v1.ObserveRequestV1 _arg0;
          _arg0 = _Parcel.readTypedObject(data, io.github.terryyyc.fakexxx.contract.v1.ObserveRequestV1.CREATOR);
          io.github.terryyyc.fakexxx.contract.v1.EnvironmentControlResultV1 _result = this.observe(_arg0);
          reply.writeNoException();
          _Parcel.writeTypedObject(reply, _result, android.os.Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
          break;
        }
        case TRANSACTION_release:
        {
          io.github.terryyyc.fakexxx.contract.v1.ReleaseRequestV1 _arg0;
          _arg0 = _Parcel.readTypedObject(data, io.github.terryyyc.fakexxx.contract.v1.ReleaseRequestV1.CREATOR);
          io.github.terryyyc.fakexxx.contract.v1.EnvironmentControlResultV1 _result = this.release(_arg0);
          reply.writeNoException();
          _Parcel.writeTypedObject(reply, _result, android.os.Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
          break;
        }
        case TRANSACTION_completeAndAdvance:
        {
          io.github.terryyyc.fakexxx.contract.v1.CompleteAndAdvanceRequestV1 _arg0;
          _arg0 = _Parcel.readTypedObject(data, io.github.terryyyc.fakexxx.contract.v1.CompleteAndAdvanceRequestV1.CREATOR);
          io.github.terryyyc.fakexxx.contract.v1.EnvironmentControlResultV1 _result = this.completeAndAdvance(_arg0);
          reply.writeNoException();
          _Parcel.writeTypedObject(reply, _result, android.os.Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
          break;
        }
        default:
        {
          return super.onTransact(code, data, reply, flags);
        }
      }
      return true;
    }
    private static class Proxy implements io.github.terryyyc.fakexxx.contract.v1.IEnvironmentControlV1
    {
      private android.os.IBinder mRemote;
      Proxy(android.os.IBinder remote)
      {
        mRemote = remote;
      }
      @Override public android.os.IBinder asBinder()
      {
        return mRemote;
      }
      public java.lang.String getInterfaceDescriptor()
      {
        return DESCRIPTOR;
      }
      @Override public io.github.terryyyc.fakexxx.contract.v1.EnvironmentControlResultV1 discover() throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        io.github.terryyyc.fakexxx.contract.v1.EnvironmentControlResultV1 _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          boolean _status = mRemote.transact(Stub.TRANSACTION_discover, _data, _reply, 0);
          _reply.readException();
          _result = _Parcel.readTypedObject(_reply, io.github.terryyyc.fakexxx.contract.v1.EnvironmentControlResultV1.CREATOR);
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      @Override public io.github.terryyyc.fakexxx.contract.v1.EnvironmentControlResultV1 preflight(io.github.terryyyc.fakexxx.contract.v1.PreflightRequestV1 request) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        io.github.terryyyc.fakexxx.contract.v1.EnvironmentControlResultV1 _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _Parcel.writeTypedObject(_data, request, 0);
          boolean _status = mRemote.transact(Stub.TRANSACTION_preflight, _data, _reply, 0);
          _reply.readException();
          _result = _Parcel.readTypedObject(_reply, io.github.terryyyc.fakexxx.contract.v1.EnvironmentControlResultV1.CREATOR);
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      @Override public io.github.terryyyc.fakexxx.contract.v1.EnvironmentControlResultV1 apply(io.github.terryyyc.fakexxx.contract.v1.ApplyRequestV1 request) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        io.github.terryyyc.fakexxx.contract.v1.EnvironmentControlResultV1 _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _Parcel.writeTypedObject(_data, request, 0);
          boolean _status = mRemote.transact(Stub.TRANSACTION_apply, _data, _reply, 0);
          _reply.readException();
          _result = _Parcel.readTypedObject(_reply, io.github.terryyyc.fakexxx.contract.v1.EnvironmentControlResultV1.CREATOR);
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      @Override public io.github.terryyyc.fakexxx.contract.v1.EnvironmentControlResultV1 observe(io.github.terryyyc.fakexxx.contract.v1.ObserveRequestV1 request) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        io.github.terryyyc.fakexxx.contract.v1.EnvironmentControlResultV1 _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _Parcel.writeTypedObject(_data, request, 0);
          boolean _status = mRemote.transact(Stub.TRANSACTION_observe, _data, _reply, 0);
          _reply.readException();
          _result = _Parcel.readTypedObject(_reply, io.github.terryyyc.fakexxx.contract.v1.EnvironmentControlResultV1.CREATOR);
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      @Override public io.github.terryyyc.fakexxx.contract.v1.EnvironmentControlResultV1 release(io.github.terryyyc.fakexxx.contract.v1.ReleaseRequestV1 request) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        io.github.terryyyc.fakexxx.contract.v1.EnvironmentControlResultV1 _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _Parcel.writeTypedObject(_data, request, 0);
          boolean _status = mRemote.transact(Stub.TRANSACTION_release, _data, _reply, 0);
          _reply.readException();
          _result = _Parcel.readTypedObject(_reply, io.github.terryyyc.fakexxx.contract.v1.EnvironmentControlResultV1.CREATOR);
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      // Complete the current schedule item and advance to the next (§6.7.3).
      //
      // The provider is the sole executor of the advance because it owns the
      // schedule order; Auto is the sole judge of quota because it owns the
      // ledger. This method is the single seam between those two ownerships, and
      // it is a compare-and-advance on THREE preconditions: the request's
      // expectedScheduleId, expectedCurrentItemId and expectedScheduleVersion, so a
      // caller that lost the race is rejected rather than silently advancing someone
      // else's item. expectedScheduleId is checked FIRST (spec v1.72, §6.7.4b step
      // 4): two schedules may reuse the same (itemId, scheduleVersion), so without
      // the identity leg the compare passes on the WRONG schedule and the advance is
      // genuinely committed there. Its value must come from the projection group
      // captured when the attempt was opened -- never re-read just before the call.
      @Override public io.github.terryyyc.fakexxx.contract.v1.EnvironmentControlResultV1 completeAndAdvance(io.github.terryyyc.fakexxx.contract.v1.CompleteAndAdvanceRequestV1 request) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        io.github.terryyyc.fakexxx.contract.v1.EnvironmentControlResultV1 _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _Parcel.writeTypedObject(_data, request, 0);
          boolean _status = mRemote.transact(Stub.TRANSACTION_completeAndAdvance, _data, _reply, 0);
          _reply.readException();
          _result = _Parcel.readTypedObject(_reply, io.github.terryyyc.fakexxx.contract.v1.EnvironmentControlResultV1.CREATOR);
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
    }
    static final int TRANSACTION_discover = (android.os.IBinder.FIRST_CALL_TRANSACTION + 0);
    static final int TRANSACTION_preflight = (android.os.IBinder.FIRST_CALL_TRANSACTION + 1);
    static final int TRANSACTION_apply = (android.os.IBinder.FIRST_CALL_TRANSACTION + 2);
    static final int TRANSACTION_observe = (android.os.IBinder.FIRST_CALL_TRANSACTION + 3);
    static final int TRANSACTION_release = (android.os.IBinder.FIRST_CALL_TRANSACTION + 4);
    static final int TRANSACTION_completeAndAdvance = (android.os.IBinder.FIRST_CALL_TRANSACTION + 5);
  }
  /** @hide */
  public static final java.lang.String DESCRIPTOR = "io.github.terryyyc.fakexxx.contract.v1.IEnvironmentControlV1";
  public io.github.terryyyc.fakexxx.contract.v1.EnvironmentControlResultV1 discover() throws android.os.RemoteException;
  public io.github.terryyyc.fakexxx.contract.v1.EnvironmentControlResultV1 preflight(io.github.terryyyc.fakexxx.contract.v1.PreflightRequestV1 request) throws android.os.RemoteException;
  public io.github.terryyyc.fakexxx.contract.v1.EnvironmentControlResultV1 apply(io.github.terryyyc.fakexxx.contract.v1.ApplyRequestV1 request) throws android.os.RemoteException;
  public io.github.terryyyc.fakexxx.contract.v1.EnvironmentControlResultV1 observe(io.github.terryyyc.fakexxx.contract.v1.ObserveRequestV1 request) throws android.os.RemoteException;
  public io.github.terryyyc.fakexxx.contract.v1.EnvironmentControlResultV1 release(io.github.terryyyc.fakexxx.contract.v1.ReleaseRequestV1 request) throws android.os.RemoteException;
  // Complete the current schedule item and advance to the next (§6.7.3).
  //
  // The provider is the sole executor of the advance because it owns the
  // schedule order; Auto is the sole judge of quota because it owns the
  // ledger. This method is the single seam between those two ownerships, and
  // it is a compare-and-advance on THREE preconditions: the request's
  // expectedScheduleId, expectedCurrentItemId and expectedScheduleVersion, so a
  // caller that lost the race is rejected rather than silently advancing someone
  // else's item. expectedScheduleId is checked FIRST (spec v1.72, §6.7.4b step
  // 4): two schedules may reuse the same (itemId, scheduleVersion), so without
  // the identity leg the compare passes on the WRONG schedule and the advance is
  // genuinely committed there. Its value must come from the projection group
  // captured when the attempt was opened -- never re-read just before the call.
  public io.github.terryyyc.fakexxx.contract.v1.EnvironmentControlResultV1 completeAndAdvance(io.github.terryyyc.fakexxx.contract.v1.CompleteAndAdvanceRequestV1 request) throws android.os.RemoteException;
  /** @hide */
  static class _Parcel {
    static private <T> T readTypedObject(
        android.os.Parcel parcel,
        android.os.Parcelable.Creator<T> c) {
      if (parcel.readInt() != 0) {
          return c.createFromParcel(parcel);
      } else {
          return null;
      }
    }
    static private <T extends android.os.Parcelable> void writeTypedObject(
        android.os.Parcel parcel, T value, int parcelableFlags) {
      if (value != null) {
        parcel.writeInt(1);
        value.writeToParcel(parcel, parcelableFlags);
      } else {
        parcel.writeInt(0);
      }
    }
  }
}

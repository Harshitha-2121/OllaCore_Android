package org.webrtc;

/**
 * Wrapper for the native webrtc::Environment handle.
 *
 * <p>Ollacore note: this class is genuinely absent from the packaged
 * com.infobip:google-webrtc:1.0.45036 classes.jar, while
 * PeerConnectionFactory$Builder references it in five places
 * (builder(), setFieldTrials(), build(), ref(), close()). The bundled
 * libjingle .so does export Java_org_webrtc_Environment_nativeCreate and
 * Java_org_webrtc_Environment_nativeFree, so supplying this thin wrapper -
 * with exactly the API surface the Builder's bytecode expects - restores
 * factory creation without adding or swapping any WebRTC dependency.
 */
public class Environment implements AutoCloseable {
  private final long webrtcEnv;

  /** Builder for {@link Environment}. */
  public static class Builder {
    public Builder setFieldTrials(String fieldTrials) {
      this.fieldTrials = fieldTrials;
      return this;
    }

    public Environment build() {
      return new Environment(this.fieldTrials);
    }

    private String fieldTrials;
  }

  public static Builder builder() {
    return new Builder();
  }

  /** Returns non-owning non-null native pointer to the webrtc::Environment. */
  public long ref() {
    return webrtcEnv;
  }

  @Override
  public void close() {
    nativeFree(webrtcEnv);
  }

  private Environment(String fieldTrials) {
    this.webrtcEnv = nativeCreate(fieldTrials);
  }

  private static native long nativeCreate(String fieldTrials);

  private static native void nativeFree(long nativeEnvironment);
}

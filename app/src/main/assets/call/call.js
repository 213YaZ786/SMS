// The encrypted call's page, run by the system WebView (core/call/WebCallMedia.kt).
//
// CallsManager is Delta Chat's calls-webapp, GPL-3.0, translated from TypeScript to plain JS:
// https://raw.githubusercontent.com/deltachat/calls-webapp/main/src/lib/calls.ts#CallsManager
// window.calls is deltachat-desktop's preload bridge, GPL-3.0, over the host's web message
// listener instead of Electron's MessagePort:
// https://raw.githubusercontent.com/deltachat/deltachat-desktop/main/packages/target-electron/static/calls-webapp-preload.js#calls
//
// Changed from calls.ts:
//   - the constructor takes two more callbacks: onEffect (the effects channel, id 0, label
//     "effects", negotiated, carrying the effect's name as a JSON string) and onConnectionState
//     (the peer connection's state, which the host reports to the call line);
//   - sendEffect(name) is new, beside reportMutedStateToRemote;
//   - logSDP is not called (see below).
//
// not ported: promptingUserToAcceptCall (Dialer's screen asks the user; the host only sends
//   #acceptCall=; offerIncomingCall= is still read and taken at once)
// not ported: resolveCallAcceptedPromise (same reason: no prompt in the page)
// not ported: camera switch inside CallsManager (calls.ts has none; the page's own glue below
//   swaps the video track when the host asks)
// not ported: playRingtone (the preload's ringtone block: Dialer's ringer rings)
// not ported: stopRingtoneAndCleanup (goes with playRingtone)
// not ported: portP (Electron's MessagePort and ipcRenderer, replaced by callsHost)
// not ported: contextBridge (the page and the bridge share one world; window.calls is set below)
// not ported: prettyType (it only prints the selected candidate pair, whose addresses are private)
// not ported: logSDP (a debug log that prints the candidates' addresses and the DTLS
//   fingerprint; the app logs neither, as CallMedia)
//   https://raw.githubusercontent.com/deltachat/calls-webapp/main/src/utils/readable-spd-log.ts#logSDP
// not ported: parseSDP, parseAttribute, parseCandidateLine, parseRtpMap, formatSummary (logSDP's)

"use strict";

// ---- the host bridge: window.calls (the preload's contextBridge.exposeInMainWorld) ----

// callsHost is the object the host puts in the page (only on the origin it allows); it has
// postMessage(string) and nothing else.
let onIceServers;
const iceServersP = new Promise((r) => (onIceServers = r));

function sendToHost(message) {
  window.callsHost.postMessage(JSON.stringify(message));
}

window.calls = {
  /** @returns {Promise<string>} */
  async getIceServers() {
    return await iceServersP;
  },
  /** @param {unknown} offerPayload */
  async startCall(offerPayload) {
    if (typeof offerPayload !== "string") {
      throw new Error(`expected a string parameter, got ${typeof offerPayload}`);
    }
    sendToHost({ type: "startCall", payload: offerPayload });
  },
  /** @param {unknown} answerPayload */
  async acceptCall(answerPayload) {
    if (typeof answerPayload !== "string") {
      throw new Error(`expected a string parameter, got ${typeof answerPayload}`);
    }
    sendToHost({ type: "acceptCall", payload: answerPayload });
  },
  endCall() {
    sendToHost({ type: "endCall" });
  },
  getAvatar() {
    return "";
  },
};

// ---- calls.ts ----

const initialRtcConfiguration = {
  iceServers: [], // To be set later by `setConfig`.
  iceTransportPolicy: "all",
  //iceTransportPolicy: "relay",

  // This is primarily to ensure that we gather only one TURN ICE candidate,
  // to avoid a situation when we send the local description
  // after gathering just one candidate without waiting
  // for the other TURN candidates to get gathered,
  // resulting in the connection failing, because our WebRTC agent
  // wanted to use other TURN candidates for the data channel or something.
  bundlePolicy: "max-bundle",
  // This should make the connection establish faster,
  // by gathering candidates prior to the `setLocalDescription` call.
  // Most notably, the `setLocalDescription` call is not performed
  // until `getUserMedia` resolves,
  // and sometimes it takes up to a second or two.
  iceCandidatePoolSize: 1,
};

class CallsManager {
  static initialState = "connecting";

  constructor(
    outStreamPromise,
    onIncomingStream,
    onStateChanged,
    onRemoteMutedStateChange,
    onIsRelayUsedChange,
    onEffect,
    onConnectionState,
  ) {
    this.outStreamPromise = outStreamPromise;
    this.onStateChanged = onStateChanged;
    this.resolveCallAcceptedPromise = undefined;
    this.peerConnection = new RTCPeerConnection(initialRtcConfiguration);

    const setIceServers = (iceServers) => {
      this.peerConnection.setConfiguration({
        ...initialRtcConfiguration,
        iceServers: iceServers,
      });
    };
    const ret = window.calls.getIceServers();
    if (typeof ret === "string") {
      setIceServers(JSON.parse(ret));
      this.setIceServersPromise = Promise.resolve();
    } else {
      this.setIceServersPromise = ret.then((r) => {
        setIceServers(JSON.parse(r));
      });
    }

    this.state = CallsManager.initialState;

    /**
     * Stores local ICE candidates to be sent to the remote peer
     * when the data channel opens.
     */
    this.iceTricklingBuffer = [];
    this.iceTricklingDataChannel = this.peerConnection.createDataChannel(
      "iceTrickling",
      {
        negotiated: true,
        id: 1,
      },
    );
    this.iceTricklingDataChannel.onmessage = async (e) => {
      const parsed = JSON.parse(e.data);
      try {
        await this.peerConnection.addIceCandidate(parsed);
      } catch (error) {
        // Suppress the error happening in older browsers.
        const expected =
          parsed == null &&
          error instanceof Error &&
          error.message.includes(
            "missing values for both sdpMid and sdpMLineIndex",
          );
        if (!expected) {
          throw error;
        }
      }
    };
    this.iceTricklingDataChannel.onopen = () => {
      for (const candidate of this.iceTricklingBuffer) {
        sendIceCandidateToDataChannel(this.iceTricklingDataChannel, candidate);
      }
      this.iceTricklingBuffer = [];
    };

    this.mutedStateDataChannel = this.peerConnection.createDataChannel(
      "mutedState",
      {
        negotiated: true,
        id: 3,
      },
    );
    this.mutedStateDataChannel.onmessage = (e) => {
      const obj = JSON.parse(e.data);
      let videoEnabled, audioEnabled;
      if (
        !(
          typeof obj === "object" &&
          obj &&
          "videoEnabled" in obj &&
          typeof (videoEnabled = obj.videoEnabled) === "boolean" &&
          "audioEnabled" in obj &&
          typeof (audioEnabled = obj.audioEnabled) === "boolean"
        )
      ) {
        console.error("remote sent an invalid mutedState message", obj);
        return;
      }

      onRemoteMutedStateChange({ audioEnabled, videoEnabled });
    };
    this.mutedStateDataChannelOpen = new Promise((resolve, reject) => {
      this.mutedStateDataChannel.onopen = () => {
        resolve();
      };
      this.mutedStateDataChannel.onclose = () => {
        reject("data channel closed");
      };
    });
    // Rejected with nobody waiting on it when the call ends first: not an error.
    this.mutedStateDataChannelOpen.catch(() => {});

    // Screen effects: a channel of its own, the same on both sides before the offer and the
    // answer, its message the effect's name as a JSON string (the SMS app's addition).
    this.effectsDataChannel = this.peerConnection.createDataChannel("effects", {
      negotiated: true,
      id: 0,
    });
    this.effectsDataChannel.onmessage = (e) => {
      if (typeof e.data !== "string" || e.data.length < 1 || e.data.length > 40) {
        return;
      }
      let name;
      try {
        name = JSON.parse(e.data);
      } catch (error) {
        // A peer running the app's native line sends the bare name.
        name = e.data;
      }
      if (typeof name === "string" && name.length >= 1 && name.length <= 32) {
        onEffect(name);
      }
    };

    this.peerConnection.ontrack = (e) => {
      const stream = e.streams[0];
      onIncomingStream(stream);
      this.state = "in-call";
      this.onStateChanged(this.state);
    };

    this.peerConnection.addEventListener("connectionstatechange", () => {
      onConnectionState(this.peerConnection.connectionState);
    });

    // Invoke `onIsRelayUsedChange` when the call is connected.
    const connectionstatechangeListener = () => {
      if (this.peerConnection.connectionState === "connected") {
        this.peerConnection.removeEventListener(
          "connectionstatechange",
          connectionstatechangeListener,
        );

        // `setTimeout` for things to settle.
        // For example, if more ICE is trickled and the pair gets switched
        // or something else IDK.
        setTimeout(() => {
          const transports = this.peerConnection
            .getSenders()
            .map((s) => s.transport?.iceTransport)
            .filter((t) => t != undefined);

          const getPairs = () =>
            transports
              .map((t) => t.getSelectedCandidatePair())
              .filter((p) => p != undefined);

          const checkRelayUsed = () => {
            const pairs = getPairs();
            if (pairs.length > 0) {
              const relayUsed = pairs.some(
                (p) => p.local.type === "relay" || p.remote.type === "relay",
              );
              onIsRelayUsedChange(relayUsed);
            } else {
              onIsRelayUsedChange(null);
            }
          };

          checkRelayUsed();
          for (const t of transports) {
            t.addEventListener("selectedcandidatepairchange", checkRelayUsed);
          }
        }, 500);
      }
    };
    this.peerConnection.addEventListener(
      "connectionstatechange",
      connectionstatechangeListener,
    );

    const acceptCall = async (payload) => {
      this.state = "connecting";
      this.onStateChanged(this.state);

      await this.setIceServersPromise;
      const gatheredEnoughIceP = gatheredEnoughIce(this.peerConnection);

      const offerObject = {
        type: "offer",
        sdp: payload,
      };
      const offerDescription = new RTCSessionDescription(offerObject);
      this.peerConnection.setRemoteDescription(offerDescription);
      const outStream = await outStreamPromise;
      outStream
        .getTracks()
        .forEach((track) => this.peerConnection.addTrack(track, outStream));
      this.peerConnection.setLocalDescription(
        await this.peerConnection.createAnswer(),
      );

      await gatheredEnoughIceP;
      const answer = this.peerConnection.localDescription.sdp;
      this.peerConnection.onicecandidate =
        this.trickleIceOverDataChannel.bind(this);
      window.calls.acceptCall(answer);
    };
    const onAnswer = (payload) => {
      const answerObject = {
        type: "answer",
        sdp: payload,
      };
      const answerDescription = new RTCSessionDescription(answerObject);

      this.peerConnection.setRemoteDescription(answerDescription);
    };

    const onHashChange = async () => {
      const hash = decodeURIComponent(window.location.hash.substring(1));
      if (!hash || hash.length === 0) {
        return;
      }

      // A new command, most likely `#acceptCall`, has been issued.
      this.resolveCallAcceptedPromise?.(false);

      if (hash === "startCall") {
        await this.startCall();
      } else if (hash.startsWith("offerIncomingCall=")) {
        // The host asks the user before it loads the page: the prompt is Dialer's, so the
        // offer is taken at once, as acceptCall= does.
        const offer = window.atob(hash.split("offerIncomingCall=", 2)[1]);
        await acceptCall(offer);
      } else if (hash.startsWith("acceptCall=")) {
        const offer = window.atob(hash.substring(11));
        await acceptCall(offer);
      } else if (hash.startsWith("onAnswer=")) {
        const answer = window.atob(hash.substring(9));
        onAnswer(answer);
      } else {
        console.log("unexpected URL hash");
      }
    };
    onHashChange();
    window.addEventListener("hashchange", onHashChange);
  }

  async startCall() {
    // Make sure to add both audio and video transceivers,
    // regardless of whether we have both camera and mic
    // (i.e. whether we do `pc.addTrack(outStreamVideoTrack, outStream)`),
    // because otherwise e.g. if we don't have camera but the remove peer does,
    // then we would need another round of negotiation,
    // but as of writing we can only do one.
    // See https://github.com/deltachat/calls-webapp/issues/90.
    this.peerConnection.addTransceiver("video", { direction: "sendrecv" });
    this.peerConnection.addTransceiver("audio", { direction: "sendrecv" });

    await this.setIceServersPromise;
    const gatheredEnoughIceP = gatheredEnoughIce(this.peerConnection);
    const outStream = await this.outStreamPromise;
    outStream
      .getTracks()
      .forEach((track) => this.peerConnection.addTrack(track, outStream));
    this.peerConnection.setLocalDescription(
      await this.peerConnection.createOffer(),
    );
    await gatheredEnoughIceP;
    const offer = this.peerConnection.localDescription.sdp;
    this.peerConnection.onicecandidate =
      this.trickleIceOverDataChannel.bind(this);
    window.calls.startCall(offer);
    this.state = "ringing";
    this.onStateChanged(this.state);
  }

  acceptCall() {
    if (this.resolveCallAcceptedPromise == undefined) {
      console.warn("acceptCall invoked, but we were not waiting for it");
      return;
    }
    this.resolveCallAcceptedPromise(true);
  }

  async endCall() {
    this.peerConnection.close();
    window.calls.endCall();

    this.resolveCallAcceptedPromise?.(false);
  }

  async reportMutedStateToRemote(mutedState) {
    if (this.mutedStateDataChannel.readyState === "connecting") {
      await this.mutedStateDataChannelOpen;
    }

    this.mutedStateDataChannel.send(JSON.stringify(mutedState));
  }

  /** A screen effect to the other side, by its name (the SMS app's addition). */
  sendEffect(name) {
    if (this.effectsDataChannel.readyState !== "open") {
      return;
    }
    this.effectsDataChannel.send(JSON.stringify(name));
  }

  getState() {
    return this.state;
  }

  trickleIceOverDataChannel(e) {
    if (this.iceTricklingDataChannel.readyState !== "open") {
      this.iceTricklingBuffer.push(e.candidate);
      return;
    }
    sendIceCandidateToDataChannel(this.iceTricklingDataChannel, e.candidate);
  }
}

function gatheredEnoughIce(pc) {
  if (pc.localDescription != null || pc.remoteDescription != null) {
    console.warn(
      "gatheredEnoughIce called after setLocalDescription " +
        "or setRemoteDescription: " +
        "it might not have captured all ICE candidate events",
    );
  }

  const gotTurnCandidate = new Promise((r) => {
    const listener = (e) => {
      if (e.candidate != null && e.candidate.type === "relay") {
        // `setTimeout` to wait just a bit,
        // just in case we receive more ICE candidates in burst.
        // But we only expect one, thanks to `bundlePolicy: "max-bundle"`.
        setTimeout(r, 0);

        pc.removeEventListener("icecandidate", listener);
      }
    };
    pc.addEventListener("icecandidate", listener);
  });

  const gotSrlfxCandidate = new Promise((r) => {
    const listener = (e) => {
      if (e.candidate != null && e.candidate.type === "srflx") {
        // `setTimeout` to wait just a bit,
        // just in case we receive more ICE candidates in burst.
        // Unlike with TURN, we wait for longer here,
        // because we might get more than 1 srlfx candidate.
        setTimeout(r, 150);

        pc.removeEventListener("icecandidate", listener);
      }
    };
    pc.addEventListener("icecandidate", listener);
  });

  const iceServers = pc.getConfiguration().iceServers;
  const haveTurnServer =
    iceServers != undefined &&
    iceServers.some((s) =>
      typeof s.urls === "string"
        ? s.urls.startsWith("turn:")
        : s.urls.some((u) => u.startsWith("turn:")),
    );

  const iceGatheringComplete = new Promise((r) => {
    const listener = () => {
      if (pc.iceGatheringState === "complete") {
        r();
        pc.removeEventListener("icegatheringstatechange", listener);
      }
    };
    pc.addEventListener("icegatheringstatechange", listener);
  });

  return Promise.race([
    // The fact that we got a TURN candidate should mean that
    // we are gonna be able to continue signaling over the TURN server,
    // thus we can send the local description now.
    gotTurnCandidate,

    // If we don't have a TURN server, then we let's be satisfied
    // with just a srflx (STUN) candidate or two.
    ...(!haveTurnServer ? [gotSrlfxCandidate] : []),

    iceGatheringComplete,
  ]);
}

function sendIceCandidateToDataChannel(dataChannel, candidate) {
  dataChannel.send(
    JSON.stringify(candidate === null ? candidate : candidate.toJSON()),
  );
}

// ---- the page around it: the other side's picture full screen, ours small inside it ----

const query = new URLSearchParams(window.location.search);
// The README's flags: audio only to start with, and no video at all.
const noOutgoingVideoInitially = query.has("noOutgoingVideoInitially");
const disableVideoCompletely = query.has("disableVideoCompletely");
// The camera the host chose, "user" (front) or "environment" (back).
let facing = query.get("facing") === "environment" ? "environment" : "user";

const remoteVideo = document.getElementById("remote");
const localVideo = document.getElementById("local");
let outStream = null;
let remoteSize = "";

function videoConstraints(face) {
  return { facingMode: face, width: { ideal: 1280 }, height: { ideal: 720 }, frameRate: { ideal: 30 } };
}

function showLocal() {
  const tracks = outStream ? outStream.getVideoTracks() : [];
  if (tracks.length === 0 || !tracks[0].enabled) {
    localVideo.classList.add("hidden");
    return;
  }
  localVideo.srcObject = new MediaStream(tracks);
  localVideo.classList.toggle("mirrored", facing === "user");
  localVideo.classList.remove("hidden");
}

const outStreamPromise = (async () => {
  const wantVideo = !(noOutgoingVideoInitially || disableVideoCompletely);
  try {
    outStream = await navigator.mediaDevices.getUserMedia({
      audio: true,
      video: wantVideo ? videoConstraints(facing) : false,
    });
  } catch (error) {
    // No camera, or refused: the call goes on with the voice.
    outStream = await navigator.mediaDevices.getUserMedia({ audio: true, video: false });
  }
  showLocal();
  return outStream;
})();

remoteVideo.onresize = () => {
  const size = remoteVideo.videoWidth + "x" + remoteVideo.videoHeight;
  if (remoteVideo.videoWidth > 0 && remoteVideo.videoHeight > 0 && size !== remoteSize) {
    remoteSize = size;
    sendToHost({ type: "size", width: remoteVideo.videoWidth, height: remoteVideo.videoHeight });
  }
};

let mutedState = { audioEnabled: true, videoEnabled: true };

const manager = new CallsManager(
  outStreamPromise,
  (stream) => {
    remoteVideo.srcObject = stream;
    if (disableVideoCompletely) {
      remoteVideo.classList.add("hidden");
    }
  },
  () => {},
  () => {},
  () => {},
  (name) => sendToHost({ type: "effect", name: name }),
  (state) => sendToHost({ type: "state", state: state }),
);

// What the host asks of the page once it runs (WebCallMedia.kt through evaluateJavascript).
window.callPage = {
  onIceServers(json) {
    onIceServers(json);
  },
  mute(on) {
    mutedState = { ...mutedState, audioEnabled: !on };
    outStreamPromise.then((stream) => {
      stream.getAudioTracks().forEach((track) => (track.enabled = !on));
      manager.reportMutedStateToRemote(mutedState).catch(() => {});
    });
  },
  sendEffect(name) {
    manager.sendEffect(name);
  },
  // "user", "environment", or null for no picture (the other side then sees none).
  async camera(face) {
    const stream = await outStreamPromise;
    const tracks = stream.getVideoTracks();
    const sender = manager.peerConnection.getSenders().find((s) => s.track && s.track.kind === "video");
    if (face === null) {
      tracks.forEach((track) => (track.enabled = false));
    } else if (!disableVideoCompletely) {
      if (tracks.length > 0 && face === facing) {
        tracks.forEach((track) => (track.enabled = true));
      } else {
        const fresh = await navigator.mediaDevices.getUserMedia({ video: videoConstraints(face) });
        const track = fresh.getVideoTracks()[0];
        tracks.forEach((old) => {
          stream.removeTrack(old);
          old.stop();
        });
        stream.addTrack(track);
        if (sender) {
          await sender.replaceTrack(track);
        }
        facing = face;
      }
    }
    mutedState = { ...mutedState, videoEnabled: face !== null };
    manager.reportMutedStateToRemote(mutedState).catch(() => {});
    showLocal();
  },
};

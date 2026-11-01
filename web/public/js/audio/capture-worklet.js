// Przechwytywanie mikrofonu w wątku audio: próbki trafiają do strony paczkami po ~2048 (ok. 40 ms przy 48 kHz)
class CaptureProcessor extends AudioWorkletProcessor {
  constructor() {
    super();
    this.buffer = new Float32Array(2048);
    this.fill = 0;
  }

  process(inputs) {
    const ch = inputs[0] && inputs[0][0];
    if (ch) {
      for (let i = 0; i < ch.length; i++) {
        this.buffer[this.fill++] = ch[i];
        if (this.fill === this.buffer.length) {
          this.port.postMessage(this.buffer, [this.buffer.buffer]);
          this.buffer = new Float32Array(2048);
          this.fill = 0;
        }
      }
    }
    return true;
  }
}
registerProcessor("hrt-capture", CaptureProcessor);

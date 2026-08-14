import { Injectable, signal } from '@angular/core';

@Injectable({
  providedIn: 'root'
})
export class AudioGuidanceService {
  readonly isMuted = signal<boolean>(false);
  private synth: SpeechSynthesis | null = null;
  private lastSpokenText = '';
  private lastSpokenTime = 0;

  constructor() {
    if (typeof window !== 'undefined' && 'speechSynthesis' in window) {
      this.synth = window.speechSynthesis;
    }
  }

  toggleMute(): boolean {
    const next = !this.isMuted();
    this.isMuted.set(next);
    if (next && this.synth) {
      this.synth.cancel();
    }
    return next;
  }

  speak(text: string, force = false): void {
    if (this.isMuted() || !this.synth) return;

    const now = Date.now();
    // Avoid repeating identical cue within 4 seconds
    if (!force && text === this.lastSpokenText && now - this.lastSpokenTime < 4000) {
      return;
    }

    this.lastSpokenText = text;
    this.lastSpokenTime = now;

    try {
      this.synth.cancel(); // Cancel any ongoing speech
      const utterance = new SpeechSynthesisUtterance(text);
      utterance.rate = 1.05;
      utterance.pitch = 1.0;
      utterance.volume = 1.0;

      // Select natural voice if available
      const voices = this.synth.getVoices();
      const preferred = voices.find(v => v.lang.startsWith('en') && (v.name.includes('Natural') || v.name.includes('Google') || v.name.includes('Samantha')));
      if (preferred) {
        utterance.voice = preferred;
      }

      this.synth.speak(utterance);
    } catch {
      // Gracefully ignore on unsupported speech engines
    }
  }

  cancel(): void {
    if (this.synth) {
      this.synth.cancel();
    }
  }
}

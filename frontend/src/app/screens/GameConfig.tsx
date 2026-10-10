import { useEffect, useRef, useState } from "react";
import {
  CARD_RULES,
  CARD_VALUES,
  GameConfig as GameConfigType,
  loadGameConfig,
  saveGameConfig
} from "../config/gameConfig";
import RulesModal, { rankName, SpecialCardRule } from "../components/RulesModal";
import { fetchProfile } from "../api/profile";
import { useAuth } from "../auth/useAuth";
import "../styles/config-layout.css";
import "../styles/select-fix.css";
import "../styles/rule-list.css";

const SAVED_TEXT = "Saved on this device for games you create.";
const UNSAVED_CHANGES_TEXT = "You have unsaved changes. Save the configuration to keep them.";
const LEAVE_WITHOUT_SAVING_TEXT = "You have unsaved changes to the game configuration. Leave without saving?";

export default function GameConfig() {
  const { token } = useAuth();
  // What is stored on this device: read at mount and again after every save. `config` is compared with it.
  const [savedConfig, setSavedConfig] = useState<GameConfigType>(() => loadGameConfig());
  const [config, setConfig] = useState<GameConfigType>(() => savedConfig);
  const [saved, setSaved] = useState(false);
  const [rulesOpen, setRulesOpen] = useState(false);
  // null while the profile is loading; only administrators see the voice switch.
  const [isAdmin, setIsAdmin] = useState<boolean | null>(null);
  // Set while a click that was already confirmed is replayed, so the link guard lets it through.
  const leaveConfirmed = useRef(false);

  const hasUnsavedChanges = configSignature(config) !== configSignature(savedConfig);

  useEffect(() => {
    let active = true;
    fetchProfile(token)
      .then((profile) => {
        if (!active) return;
        setIsAdmin(profile.canClearGames);
        if (!profile.canClearGames) {
          // Drop a voice setting saved while this account was an administrator.
          setConfig((current) => ({ ...current, voiceEnabled: false }));
          const stored = loadGameConfig();
          if (stored.voiceEnabled) saveGameConfig({ ...stored, voiceEnabled: false });
          // The correction is in storage now, so it must not count as an unsaved change.
          setSavedConfig(loadGameConfig());
        }
      })
      .catch(() => { if (active) setIsAdmin(false); });
    return () => { active = false; };
  }, [token]);

  // Closing or reloading the tab: the listener is installed only while there are unsaved changes.
  useEffect(() => {
    if (!hasUnsavedChanges) return;
    const warnBeforeUnload = (event: BeforeUnloadEvent) => {
      event.preventDefault();
      event.returnValue = "";
    };
    window.addEventListener("beforeunload", warnBeforeUnload);
    return () => window.removeEventListener("beforeunload", warnBeforeUnload);
  }, [hasUnsavedChanges]);

  // Leaving by a link: the app uses BrowserRouter, so useBlocker is not available. Confirm on clicks that would
  // change the page (menu links and other in-app links). Browser back/forward is not covered.
  useEffect(() => {
    if (!hasUnsavedChanges) return;
    const confirmLeaveOnLinkClick = (event: MouseEvent) => {
      if (leaveConfirmed.current || event.defaultPrevented || event.button !== 0) return;
      if (event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
      const link = event.target instanceof Element ? event.target.closest("a[href]") : null;
      if (!(link instanceof HTMLAnchorElement)) return;
      if (link.origin !== window.location.origin || link.hasAttribute("download")) return;
      if (link.target && link.target !== "_self") return;
      if (link.pathname === window.location.pathname && link.search === window.location.search) return;
      event.preventDefault();
      event.stopPropagation();
      if (!window.confirm(LEAVE_WITHOUT_SAVING_TEXT)) return;
      // Replay the click without this guard so the router performs the navigation as usual.
      leaveConfirmed.current = true;
      try {
        link.click();
      } finally {
        leaveConfirmed.current = false;
      }
    };
    document.addEventListener("click", confirmLeaveOnLinkClick, true);
    return () => document.removeEventListener("click", confirmLeaveOnLinkClick, true);
  }, [hasUnsavedChanges]);

  const updateConfig = (patch: Partial<GameConfigType>) => {
    setConfig((current) => ({ ...current, ...patch }));
  };

  const selectDeckCount = (decksCount: 1 | 2) => {
    updateConfig({ decksCount, burnCount: decksCount === 1 ? 4 : 6 });
  };

  const save = () => {
    saveGameConfig({ ...config, voiceEnabled: isAdmin === true && config.voiceEnabled });
    // Show exactly what was stored, so the screen and the snapshot agree after a save.
    const stored = loadGameConfig();
    setSavedConfig(stored);
    setConfig(stored);
    setSaved(true);
  };

  // Generated from the current (possibly unsaved) selections so the popup matches what the player picked.
  const specialRules: SpecialCardRule[] = CARD_VALUES.flatMap((value) => {
    const rule = config.cardRules[String(value)];
    return rule && rule !== "DEFAULT" ? [{ value, rule }] : [];
  });

  return (
    <div className="menu-content fade-in">
      <div className="topbar">
        <div>
          <div className="badge">Next Game</div>
          <h2 className="title">Game Configuration</h2>
        </div>
      </div>

      <div className={`config-layout${isAdmin === true ? " has-voice" : ""}`}>
        <section className="glass card config-howto">
          <h3 className="title">How to play</h3>
          <p className="config-description">
            Read the rules of Shithead, including the special cards selected for this configuration.
          </p>
          <button
            className="button secondary"
            type="button"
            aria-haspopup="dialog"
            aria-expanded={rulesOpen}
            onClick={() => setRulesOpen(true)}
          >
            How to play
          </button>
        </section>

        <section className="glass card config-decks">
          <h3 className="title">Decks</h3>
          <p className="config-description">Choose how many standard decks the next game will use. Up to 10 players can play; use 2 decks for more than 5.</p>
          <div className="choice-switch" role="group" aria-label="Number of decks">
            {([1, 2] as const).map((count) => (
              <button
                key={count}
                className={`choice-switch-option${config.decksCount === count ? " active" : ""}`}
                type="button"
                aria-pressed={config.decksCount === count}
                onClick={() => selectDeckCount(count)}
              >
                {count} {count === 1 ? "deck" : "decks"}
              </button>
            ))}
          </div>
          <p className="config-note">Burn the table when {config.burnCount} cards of the same rank are stacked.</p>
        </section>

        <section className="glass card config-face-up-card" aria-labelledby="face-up-cards-title">
          <h3 className="title" id="face-up-cards-title">Face-up cards</h3>
          <p className="config-description">Choose how face-up cards can be played.</p>

          <div className="config-option">
            <h4 className="config-option-title" id="mixed-face-up-label">Play Face-Up Cards with Your Hand</h4>
            <p className="config-note">
              When the draw pile is empty, allow matching face-up cards to be played with cards from your hand.
            </p>
            <OnOffSwitch
              labelId="mixed-face-up-label"
              value={config.allowMixedHandAndFaceUpWhenDeckEmpty}
              onChange={(allowMixedHandAndFaceUpWhenDeckEmpty) => updateConfig({ allowMixedHandAndFaceUpWhenDeckEmpty })}
            />
          </div>

          <div className="config-option">
            <h4 className="config-option-title" id="failed-face-up-label">
              Pick up the pile when a face-up card can&apos;t be played
            </h4>
            <p className="config-note">
              When your hand is empty and the face-up card you choose cannot be played, it is placed on the pile and you
              pick up the whole pile, like a failed blind flip. When off, an illegal face-up play is rejected.
            </p>
            <OnOffSwitch
              labelId="failed-face-up-label"
              value={config.allowFailedFaceUpPlay}
              onChange={(allowFailedFaceUpPlay) => updateConfig({ allowFailedFaceUpPlay })}
            />
          </div>
        </section>

        {isAdmin === true && (
          <section className="glass card config-voice-card" aria-labelledby="voice-chat-title">
            <h3 className="title" id="voice-chat-title">Voice chat</h3>
            <p className="config-description">
              Let players in the next game you create talk over voice. Only administrators can turn this on.
            </p>
            <div className="config-option">
              <h4 className="config-option-title" id="voice-chat-label">Voice chat for this game</h4>
              <p className="config-note">
                Players press Join voice to use the microphone. Voice is processed by LiveKit; nobody is recorded.
              </p>
              <OnOffSwitch
                labelId="voice-chat-label"
                value={config.voiceEnabled}
                onChange={(voiceEnabled) => updateConfig({ voiceEnabled })}
              />
            </div>
          </section>
        )}

        <section className="glass card config-rules-card" aria-labelledby="card-rules-title">
          <h3 className="title" id="card-rules-title">Card Rules</h3>
          <p className="config-description">
            Choose the existing special effect for each rank. These rules are saved with each game you create.
          </p>
          <div className="rules-table-scroll">
            <table className="rules-table">
              <thead>
                <tr><th scope="col">Card rank</th><th scope="col">Rule</th></tr>
              </thead>
              <tbody>
                {CARD_VALUES.map((value) => (
                  <tr key={value}>
                    <th scope="row">{rankName(value)}</th>
                    <td>
                      <select
                        className="input rules-select"
                        aria-label={`Rule for ${value}`}
                        value={config.cardRules[String(value)]}
                        onChange={(event) => updateConfig({
                          cardRules: { ...config.cardRules, [String(value)]: event.target.value as GameConfigType["cardRules"][string] }
                        })}
                      >
                        {CARD_RULES.map((rule) => <option key={rule} value={rule}>{ruleLabel(rule)}</option>)}
                      </select>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <ul className="config-note rule-list">
            <li><strong>Joker</strong> is playable on anything.</li>
            <li><strong>Smaller</strong> restricts the next card.</li>
            <li><strong>Transparent</strong> ignores the card below it.</li>
            <li><strong>Reverse</strong> changes turn order.</li>
            <li><strong>Burner</strong> clears the pile and gives you another turn.</li>
          </ul>
        </section>

        <div className="config-actions">
          <button className="button config-save-button" type="button" onClick={save}>Save Configuration</button>
          <p className={`config-status${hasUnsavedChanges ? " is-unsaved" : ""}`} role="status">
            {hasUnsavedChanges ? UNSAVED_CHANGES_TEXT : saved ? SAVED_TEXT : ""}
          </p>
        </div>
      </div>

      {rulesOpen && (
        <RulesModal
          specialRules={specialRules}
          faceUpFailurePickup={config.allowFailedFaceUpPlay}
          onClose={() => setRulesOpen(false)}
        />
      )}
    </div>
  );
}

/** Canonical form of a configuration, so equal settings always produce the same string. */
function configSignature(config: GameConfigType): string {
  return JSON.stringify({
    allowMixedHandAndFaceUpWhenDeckEmpty: Boolean(config.allowMixedHandAndFaceUpWhenDeckEmpty),
    allowFailedFaceUpPlay: Boolean(config.allowFailedFaceUpPlay),
    decksCount: config.decksCount,
    burnCount: config.burnCount,
    voiceEnabled: Boolean(config.voiceEnabled),
    cardRules: CARD_VALUES.map((value) => config.cardRules[String(value)] ?? "DEFAULT")
  });
}

function OnOffSwitch({ labelId, value, onChange }: { labelId: string; value: boolean; onChange: (next: boolean) => void }) {
  return (
    <div className="choice-switch" role="group" aria-labelledby={labelId}>
      {([false, true] as const).map((option) => (
        <button
          key={option ? "on" : "off"}
          className={`choice-switch-option${value === option ? " active" : ""}`}
          type="button"
          aria-pressed={value === option}
          onClick={() => onChange(option)}
        >
          {option ? "On" : "Off"}
        </button>
      ))}
    </div>
  );
}

function ruleLabel(rule: typeof CARD_RULES[number]) {
  return rule === "DEFAULT" ? "Standard" : rule.charAt(0) + rule.slice(1).toLowerCase();
}

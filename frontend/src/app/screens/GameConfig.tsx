import { useEffect, useState } from "react";
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

export default function GameConfig() {
  const { token } = useAuth();
  const [config, setConfig] = useState<GameConfigType>(() => loadGameConfig());
  const [saved, setSaved] = useState(false);
  const [rulesOpen, setRulesOpen] = useState(false);
  // null while the profile is loading; only administrators see the voice switch.
  const [isAdmin, setIsAdmin] = useState<boolean | null>(null);

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
        }
      })
      .catch(() => { if (active) setIsAdmin(false); });
    return () => { active = false; };
  }, [token]);

  const updateConfig = (patch: Partial<GameConfigType>) => {
    setConfig((current) => ({ ...current, ...patch }));
    setSaved(false);
  };

  const selectDeckCount = (decksCount: 1 | 2) => {
    updateConfig({ decksCount, burnCount: decksCount === 1 ? 4 : 6 });
  };

  const save = () => {
    saveGameConfig({ ...config, voiceEnabled: isAdmin === true && config.voiceEnabled });
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
          <p className="config-description">Choose how many standard decks the next game will use.</p>
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
          <p className="config-note">Joker is playable on anything. Smaller restricts the next card; Transparent ignores the card below it; Reverse changes turn order; Burner clears the pile and gives you another turn.</p>
        </section>

        <div className="config-actions">
          {saved && <span className="config-saved" role="status">Saved on this device for games you create.</span>}
          <button className="button" type="button" onClick={save}>Save Configuration</button>
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

function OnOffSwitch({ labelId, value, onChange }: { labelId: string; value: boolean; onChange: (next: boolean) => void }) {
  return (
    <div className="choice-switch" role="group" aria-labelledby={labelId}>
      {([true, false] as const).map((option) => (
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

import { useState } from "react";
import { useNavigate } from "react-router-dom";
import {
  CARD_RULES,
  CARD_VALUES,
  GameConfig as GameConfigType,
  loadGameConfig,
  saveGameConfig
} from "../config/gameConfig";

export default function GameConfig() {
  const navigate = useNavigate();
  const [config, setConfig] = useState<GameConfigType>(() => loadGameConfig());
  const [saved, setSaved] = useState(false);

  const updateConfig = (patch: Partial<GameConfigType>) => {
    setConfig((current) => ({ ...current, ...patch }));
    setSaved(false);
  };

  const selectDeckCount = (decksCount: 1 | 2) => {
    updateConfig({ decksCount, burnCount: decksCount === 1 ? 4 : 6 });
  };

  const save = () => {
    saveGameConfig(config);
    setSaved(true);
  };

  return (
    <div className="page fade-in">
      <div className="topbar">
        <div>
          <div className="badge">Next Game</div>
          <h2 className="title">Game Configuration</h2>
        </div>
        <button className="button secondary" type="button" onClick={() => navigate("/lobby")}>
          Back to Lobby
        </button>
      </div>

      <div className="config-layout">
        <section className="glass card">
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

        <section className="glass card">
          <h3 className="title">Play Face-Up Cards with Your Hand</h3>
          <label className="config-toggle-row">
            <input
              type="checkbox"
              checked={config.allowMixedHandAndFaceUpWhenDeckEmpty}
              onChange={(event) => updateConfig({ allowMixedHandAndFaceUpWhenDeckEmpty: event.target.checked })}
            />
            <span>When the draw pile is empty, allow matching face-up cards to be played with cards from your hand.</span>
          </label>
        </section>

        <section className="glass card config-rules-card">
          <h3 className="title">Card Rules</h3>
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
                    <th scope="row">{value === 11 ? "Jack" : value === 12 ? "Queen" : value === 13 ? "King" : value === 14 ? "Ace" : value}</th>
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
    </div>
  );
}

function ruleLabel(rule: typeof CARD_RULES[number]) {
  return rule === "DEFAULT" ? "Standard" : rule.charAt(0) + rule.slice(1).toLowerCase();
}

export const GAME_CONFIG_STORAGE_KEY = "shithead_game_config";

export const CARD_VALUES = Array.from({ length: 13 }, (_, index) => index + 2);

export const CARD_RULES = [
  "DEFAULT",
  "JOKER",
  "SMALLER",
  "TRANSPARENT",
  "REVERSE",
  "BURNER"
] as const;

export type CardRule = typeof CARD_RULES[number];

export type GameConfig = {
  allowMixedHandAndFaceUpWhenDeckEmpty: boolean;
  allowFailedFaceUpPlay: boolean;
  decksCount: 1 | 2;
  burnCount: 4 | 6;
  cardRules: Record<string, CardRule>;
  /** Only administrators can turn this on; the server rejects it for other players. */
  voiceEnabled: boolean;
};

const DEFAULT_CARD_RULES: Record<string, CardRule> = {
  "2": "JOKER",
  "3": "DEFAULT",
  "4": "DEFAULT",
  "5": "DEFAULT",
  "6": "SMALLER",
  "7": "DEFAULT",
  "8": "TRANSPARENT",
  "9": "REVERSE",
  "10": "BURNER",
  "11": "DEFAULT",
  "12": "DEFAULT",
  "13": "DEFAULT",
  "14": "DEFAULT"
};

export const DEFAULT_GAME_CONFIG: GameConfig = {
  allowMixedHandAndFaceUpWhenDeckEmpty: false,
  allowFailedFaceUpPlay: false,
  decksCount: 1,
  burnCount: 4,
  cardRules: DEFAULT_CARD_RULES,
  voiceEnabled: false
};

export function loadGameConfig(): GameConfig {
  try {
    const saved = localStorage.getItem(GAME_CONFIG_STORAGE_KEY);
    if (!saved) return DEFAULT_GAME_CONFIG;
    const parsed = JSON.parse(saved) as Partial<GameConfig>;
    const decksCount: 1 | 2 = parsed.decksCount === 2 ? 2 : 1;
    const rules = parsed.cardRules && typeof parsed.cardRules === "object"
      ? parsed.cardRules
      : {};
    const cardRules = { ...DEFAULT_CARD_RULES };
    for (const value of CARD_VALUES) {
      const rule = rules[String(value)];
      if (CARD_RULES.includes(rule as CardRule)) cardRules[String(value)] = rule as CardRule;
    }
    return {
      allowMixedHandAndFaceUpWhenDeckEmpty: Boolean(parsed.allowMixedHandAndFaceUpWhenDeckEmpty),
      allowFailedFaceUpPlay: Boolean(parsed.allowFailedFaceUpPlay),
      decksCount,
      burnCount: decksCount === 2 ? 6 : 4,
      cardRules,
      voiceEnabled: Boolean(parsed.voiceEnabled)
    };
  } catch {
    return DEFAULT_GAME_CONFIG;
  }
}

export function saveGameConfig(config: GameConfig): void {
  localStorage.setItem(GAME_CONFIG_STORAGE_KEY, JSON.stringify(config));
}

export function getCreateGameConfig(config: GameConfig) {
  const cardRules = Object.fromEntries(
    CARD_VALUES.map((value) => [String(value), config.cardRules[String(value)] ?? "DEFAULT"])
  );
  return {
    ...config,
    allowFailedFaceUpPlay: Boolean(config.allowFailedFaceUpPlay),
    voiceEnabled: Boolean(config.voiceEnabled),
    cardRules,
    alwaysPlayable: CARD_VALUES.filter((value) => ["JOKER", "TRANSPARENT"].includes(cardRules[String(value)])),
    canPlayAgain: CARD_VALUES.filter((value) => cardRules[String(value)] === "BURNER")
  };
}

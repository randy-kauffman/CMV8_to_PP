// GENERADO por herramientas/generar_desde_diseno.py desde docs/diseno/anatomia-y-tokens/tokens.json.
// No editar a mano: cambiar tokens.json y regenerar. Se embebe como constante porque el PCF no lee archivos.

export type Variante = "fluent" | "institucional" | "moderno";
export type ModoColor = "light" | "dark";
export type Densidad = "compact" | "comfortable";

/** Valor de un token por variante: fijo, por modo de color o por densidad (meta.valueShapes de tokens.json). */
export type ValorToken = string | { readonly light: string; readonly dark: string } | { readonly compact: string; readonly comfortable: string };

export interface DefinicionToken {
    readonly css: string;
    readonly maker?: NombreMaker;
    readonly valores: Readonly<Record<Variante, ValorToken>>;
}

export type NombreMaker =
    | "PrimaryColor"
    | "PrimaryHoverColor"
    | "OnPrimaryColor"
    | "BackgroundColor"
    | "SurfaceColor"
    | "BorderColor"
    | "TextColor"
    | "TextSecondaryColor"
    | "SuccessColor"
    | "WarningColor"
    | "InfoColor"
    | "DangerColor"
    | "NeutralColor"
    | "FontFamily"
    | "FontSize"
    | "FontScale"
    | "BorderRadius"
    | "BorderWidth";

export const NOMBRES_MAKER: readonly NombreMaker[] = ["PrimaryColor", "PrimaryHoverColor", "OnPrimaryColor", "BackgroundColor", "SurfaceColor", "BorderColor", "TextColor", "TextSecondaryColor", "SuccessColor", "WarningColor", "InfoColor", "DangerColor", "NeutralColor", "FontFamily", "FontSize", "FontScale", "BorderRadius", "BorderWidth"];

export const TOKENS: readonly DefinicionToken[] = [
    { css: "--fu-color-primary", maker: "PrimaryColor", valores: { fluent: { light: "#0F6CBD", dark: "#115EA3" }, institucional: { light: "#0F6CBD", dark: "#115EA3" }, moderno: { light: "#0F6CBD", dark: "#115EA3" } } },
    { css: "--fu-color-primary-hover", maker: "PrimaryHoverColor", valores: { fluent: { light: "#115EA3", dark: "#0F6CBD" }, institucional: { light: "#115EA3", dark: "#0F6CBD" }, moderno: { light: "#115EA3", dark: "#0F6CBD" } } },
    { css: "--fu-color-primary-pressed", valores: { fluent: { light: "#0C3B5E", dark: "#0F548C" }, institucional: { light: "#0C3B5E", dark: "#0F548C" }, moderno: { light: "#0C3B5E", dark: "#0F548C" } } },
    { css: "--fu-color-on-primary", maker: "OnPrimaryColor", valores: { fluent: { light: "#FFFFFF", dark: "#FFFFFF" }, institucional: { light: "#FFFFFF", dark: "#FFFFFF" }, moderno: { light: "#FFFFFF", dark: "#FFFFFF" } } },
    { css: "--fu-color-primary-text", valores: { fluent: { light: "#0F6CBD", dark: "#479EF5" }, institucional: { light: "#0F6CBD", dark: "#479EF5" }, moderno: { light: "#0F6CBD", dark: "#479EF5" } } },
    { css: "--fu-color-primary-subtle", valores: { fluent: { light: "#EBF3FC", dark: "#082338" }, institucional: { light: "#EEF3F9", dark: "#0E1A28" }, moderno: { light: "#EBF3FC", dark: "#0F2036" } } },
    { css: "--fu-color-bg", maker: "BackgroundColor", valores: { fluent: { light: "#FFFFFF", dark: "#292929" }, institucional: { light: "#FFFFFF", dark: "#121418" }, moderno: { light: "#F6F7F9", dark: "#15171B" } } },
    { css: "--fu-color-surface", maker: "SurfaceColor", valores: { fluent: { light: "#FFFFFF", dark: "#292929" }, institucional: { light: "#FFFFFF", dark: "#181B20" }, moderno: { light: "#FFFFFF", dark: "#1E2126" } } },
    { css: "--fu-color-surface-alt", valores: { fluent: { light: "#FAFAFA", dark: "#1F1F1F" }, institucional: { light: "#F3F4F6", dark: "#20242A" }, moderno: { light: "#F1F3F6", dark: "#262A30" } } },
    { css: "--fu-color-surface-hover", valores: { fluent: { light: "#F5F5F5", dark: "#3D3D3D" }, institucional: { light: "#EEF0F3", dark: "#272C33" }, moderno: { light: "#EAEDF2", dark: "#2E333A" } } },
    { css: "--fu-color-border", maker: "BorderColor", valores: { fluent: { light: "#E0E0E0", dark: "#3D3D3D" }, institucional: { light: "#DDE1E6", dark: "#2B3038" }, moderno: { light: "#E2E6EC", dark: "#353A42" } } },
    { css: "--fu-color-border-strong", valores: { fluent: { light: "#757575", dark: "#8F8F8F" }, institucional: { light: "#6B7480", dark: "#8A939E" }, moderno: { light: "#8A93A0", dark: "#6F7782" } } },
    { css: "--fu-color-text", maker: "TextColor", valores: { fluent: { light: "#242424", dark: "#FFFFFF" }, institucional: { light: "#111418", dark: "#F2F4F6" }, moderno: { light: "#1B1F24", dark: "#EEF0F3" } } },
    { css: "--fu-color-text-secondary", maker: "TextSecondaryColor", valores: { fluent: { light: "#616161", dark: "#ADADAD" }, institucional: { light: "#3F4752", dark: "#B9C0C8" }, moderno: { light: "#5A6270", dark: "#A7AEB8" } } },
    { css: "--fu-color-text-disabled", valores: { fluent: { light: "#BDBDBD", dark: "#5C5C5C" }, institucional: { light: "#A0A7B0", dark: "#5A626C" }, moderno: { light: "#AEB4BD", dark: "#575D66" } } },
    { css: "--fu-color-focus", valores: { fluent: { light: "#000000", dark: "#FFFFFF" }, institucional: { light: "#111418", dark: "#F2F4F6" }, moderno: { light: "#0F6CBD", dark: "#7FB6F5" } } },
    { css: "--fu-color-scrim", valores: { fluent: { light: "rgba(0,0,0,0.40)", dark: "rgba(0,0,0,0.60)" }, institucional: { light: "rgba(17,20,24,0.45)", dark: "rgba(0,0,0,0.65)" }, moderno: { light: "rgba(16,24,40,0.35)", dark: "rgba(0,0,0,0.60)" } } },
    { css: "--fu-color-status-success-fg", maker: "SuccessColor", valores: { fluent: { light: "#0E700E", dark: "#54B054" }, institucional: { light: "#1E6B34", dark: "#7CC48F" }, moderno: { light: "#0B7A3E", dark: "#6FD39A" } } },
    { css: "--fu-color-status-success-bg", valores: { fluent: { light: "#F1FAF1", dark: "#052505" }, institucional: { light: "#F2F7F3", dark: "#132219" }, moderno: { light: "#E6F6EC", dark: "#0F2A1B" } } },
    { css: "--fu-color-status-success-border", valores: { fluent: { light: "#9FD89F", dark: "#107C10" }, institucional: { light: "#7FAE8B", dark: "#2F5E3D" }, moderno: { light: "#B5E3C6", dark: "#1F5B38" } } },
    { css: "--fu-color-status-warning-fg", maker: "WarningColor", valores: { fluent: { light: "#9A3F06", dark: "#FAA06B" }, institucional: { light: "#8C4A00", dark: "#E9B26B" }, moderno: { light: "#9A4A00", dark: "#FFB866" } } },
    { css: "--fu-color-status-warning-bg", valores: { fluent: { light: "#FFF9F5", dark: "#411200" }, institucional: { light: "#FBF5EC", dark: "#2A1C08" }, moderno: { light: "#FFF3E3", dark: "#2E1D07" } } },
    { css: "--fu-color-status-warning-border", valores: { fluent: { light: "#FDCFB4", dark: "#BC4B09" }, institucional: { light: "#D2A867", dark: "#6E4A12" }, moderno: { light: "#FFD49A", dark: "#6B4310" } } },
    { css: "--fu-color-status-info-fg", maker: "InfoColor", valores: { fluent: { light: "#0F548C", dark: "#62ABF5" }, institucional: { light: "#1F4E8C", dark: "#8FB3E6" }, moderno: { light: "#1659A8", dark: "#82B6F5" } } },
    { css: "--fu-color-status-info-bg", valores: { fluent: { light: "#EBF3FC", dark: "#082338" }, institucional: { light: "#F1F5FA", dark: "#111B2A" }, moderno: { light: "#E8F1FC", dark: "#0F2036" } } },
    { css: "--fu-color-status-info-border", valores: { fluent: { light: "#B4D6FA", dark: "#0F548C" }, institucional: { light: "#8FA9CC", dark: "#2F4A73" }, moderno: { light: "#B9D3F5", dark: "#24497A" } } },
    { css: "--fu-color-status-danger-fg", maker: "DangerColor", valores: { fluent: { light: "#B10E1C", dark: "#DC626D" }, institucional: { light: "#A11D24", dark: "#E8898F" }, moderno: { light: "#B42330", dark: "#FF8A94" } } },
    { css: "--fu-color-status-danger-bg", valores: { fluent: { light: "#FDF3F4", dark: "#3B0509" }, institucional: { light: "#FAF1F1", dark: "#2B1314" }, moderno: { light: "#FDECEE", dark: "#311317" } } },
    { css: "--fu-color-status-danger-border", valores: { fluent: { light: "#EEACB2", dark: "#B10E1C" }, institucional: { light: "#D59397", dark: "#7A2A2F" }, moderno: { light: "#F5B9BF", dark: "#7A2730" } } },
    { css: "--fu-color-status-neutral-fg", maker: "NeutralColor", valores: { fluent: { light: "#616161", dark: "#ADADAD" }, institucional: { light: "#4F5660", dark: "#B9C0C8" }, moderno: { light: "#5A6270", dark: "#A7AEB8" } } },
    { css: "--fu-color-status-neutral-bg", valores: { fluent: { light: "#F5F5F5", dark: "#333333" }, institucional: { light: "#F3F4F6", dark: "#20242A" }, moderno: { light: "#EFF1F4", dark: "#262A30" } } },
    { css: "--fu-color-status-neutral-border", valores: { fluent: { light: "#D1D1D1", dark: "#525252" }, institucional: { light: "#A9AFB7", dark: "#4A525C" }, moderno: { light: "#D5DAE1", dark: "#3A4049" } } },
    { css: "--fu-font-family", maker: "FontFamily", valores: { fluent: "\"Segoe UI\", \"Segoe UI Web (West European)\", -apple-system, BlinkMacSystemFont, Roboto, \"Helvetica Neue\", sans-serif", institucional: "\"Segoe UI\", \"Segoe UI Web (West European)\", -apple-system, BlinkMacSystemFont, Roboto, \"Helvetica Neue\", sans-serif", moderno: "\"Segoe UI\", \"Segoe UI Web (West European)\", -apple-system, BlinkMacSystemFont, Roboto, \"Helvetica Neue\", sans-serif" } },
    { css: "--fu-font-family-heading", valores: { fluent: "\"Segoe UI\", \"Segoe UI Web (West European)\", -apple-system, BlinkMacSystemFont, Roboto, \"Helvetica Neue\", sans-serif", institucional: "Cambria, Georgia, \"Times New Roman\", serif", moderno: "\"Segoe UI\", \"Segoe UI Web (West European)\", -apple-system, BlinkMacSystemFont, Roboto, \"Helvetica Neue\", sans-serif" } },
    { css: "--fu-font-size-base", maker: "FontSize", valores: { fluent: "14px", institucional: "13px", moderno: "14px" } },
    { css: "--fu-font-scale", maker: "FontScale", valores: { fluent: "1.143", institucional: "1.125", moderno: "1.2" } },
    { css: "--fu-font-size-caption", valores: { fluent: "12px", institucional: "12px", moderno: "12px" } },
    { css: "--fu-font-size-body", valores: { fluent: "14px", institucional: "13px", moderno: "14px" } },
    { css: "--fu-font-size-subtitle", valores: { fluent: "16px", institucional: "15px", moderno: "17px" } },
    { css: "--fu-font-size-title", valores: { fluent: "20px", institucional: "17px", moderno: "20px" } },
    { css: "--fu-font-line-caption", valores: { fluent: "16px", institucional: "16px", moderno: "16px" } },
    { css: "--fu-font-line-body", valores: { fluent: "20px", institucional: "18px", moderno: "20px" } },
    { css: "--fu-font-line-subtitle", valores: { fluent: "22px", institucional: "20px", moderno: "24px" } },
    { css: "--fu-font-line-title", valores: { fluent: "28px", institucional: "24px", moderno: "28px" } },
    { css: "--fu-font-weight-regular", valores: { fluent: "400", institucional: "400", moderno: "400" } },
    { css: "--fu-font-weight-semibold", valores: { fluent: "600", institucional: "600", moderno: "600" } },
    { css: "--fu-font-tracking-label", valores: { fluent: "0", institucional: "0.04em", moderno: "0" } },
    { css: "--fu-font-numeric", valores: { fluent: "tabular-nums", institucional: "tabular-nums", moderno: "tabular-nums" } },
    { css: "--fu-radius-sm", valores: { fluent: "2px", institucional: "0px", moderno: "6px" } },
    { css: "--fu-radius-md", maker: "BorderRadius", valores: { fluent: "4px", institucional: "2px", moderno: "10px" } },
    { css: "--fu-radius-lg", valores: { fluent: "8px", institucional: "2px", moderno: "16px" } },
    { css: "--fu-radius-chip", valores: { fluent: "4px", institucional: "2px", moderno: "999px" } },
    { css: "--fu-border-width", maker: "BorderWidth", valores: { fluent: "1px", institucional: "1px", moderno: "1px" } },
    { css: "--fu-border-width-dropzone", valores: { fluent: "1px", institucional: "1px", moderno: "2px" } },
    { css: "--fu-focus-width", valores: { fluent: "2px", institucional: "2px", moderno: "2px" } },
    { css: "--fu-focus-offset", valores: { fluent: "2px", institucional: "1px", moderno: "2px" } },
    { css: "--fu-shadow-card", valores: { fluent: { light: "0 0 2px rgba(0,0,0,0.12), 0 1px 2px rgba(0,0,0,0.14)", dark: "0 0 2px rgba(0,0,0,0.24), 0 1px 2px rgba(0,0,0,0.28)" }, institucional: { light: "none", dark: "none" }, moderno: { light: "0 1px 2px rgba(16,24,40,0.06), 0 1px 3px rgba(16,24,40,0.10)", dark: "0 1px 3px rgba(0,0,0,0.40)" } } },
    { css: "--fu-shadow-panel", valores: { fluent: { light: "0 0 2px rgba(0,0,0,0.12), 0 8px 16px rgba(0,0,0,0.14)", dark: "0 0 2px rgba(0,0,0,0.24), 0 8px 16px rgba(0,0,0,0.28)" }, institucional: { light: "0 0 0 1px rgba(17,20,24,0.12), 0 4px 12px rgba(17,20,24,0.10)", dark: "0 0 0 1px rgba(255,255,255,0.08), 0 4px 12px rgba(0,0,0,0.50)" }, moderno: { light: "0 12px 32px rgba(16,24,40,0.16)", dark: "0 12px 32px rgba(0,0,0,0.55)" } } },
    { css: "--fu-space-1", valores: { fluent: "2px", institucional: "2px", moderno: "2px" } },
    { css: "--fu-space-2", valores: { fluent: "4px", institucional: "4px", moderno: "4px" } },
    { css: "--fu-space-3", valores: { fluent: "8px", institucional: "8px", moderno: "8px" } },
    { css: "--fu-space-4", valores: { fluent: "12px", institucional: "12px", moderno: "12px" } },
    { css: "--fu-space-5", valores: { fluent: "16px", institucional: "16px", moderno: "16px" } },
    { css: "--fu-space-6", valores: { fluent: "20px", institucional: "20px", moderno: "20px" } },
    { css: "--fu-space-7", valores: { fluent: "24px", institucional: "24px", moderno: "24px" } },
    { css: "--fu-space-8", valores: { fluent: "32px", institucional: "32px", moderno: "32px" } },
    { css: "--fu-size-control", valores: { fluent: { compact: "24px", comfortable: "32px" }, institucional: { compact: "24px", comfortable: "28px" }, moderno: { compact: "32px", comfortable: "40px" } } },
    { css: "--fu-size-file-row", valores: { fluent: { compact: "44px", comfortable: "56px" }, institucional: { compact: "40px", comfortable: "48px" }, moderno: { compact: "56px", comfortable: "72px" } } },
    { css: "--fu-size-grid-row", valores: { fluent: { compact: "36px", comfortable: "44px" }, institucional: { compact: "32px", comfortable: "40px" }, moderno: { compact: "44px", comfortable: "56px" } } },
    { css: "--fu-size-grid-header", valores: { fluent: { compact: "32px", comfortable: "40px" }, institucional: { compact: "32px", comfortable: "36px" }, moderno: { compact: "36px", comfortable: "44px" } } },
    { css: "--fu-size-toolbar", valores: { fluent: { compact: "40px", comfortable: "48px" }, institucional: { compact: "36px", comfortable: "44px" }, moderno: { compact: "44px", comfortable: "52px" } } },
    { css: "--fu-space-row-x", valores: { fluent: { compact: "8px", comfortable: "12px" }, institucional: { compact: "8px", comfortable: "12px" }, moderno: { compact: "12px", comfortable: "16px" } } },
    { css: "--fu-space-row-y", valores: { fluent: { compact: "6px", comfortable: "8px" }, institucional: { compact: "4px", comfortable: "6px" }, moderno: { compact: "10px", comfortable: "14px" } } },
    { css: "--fu-space-list-gap", valores: { fluent: { compact: "0px", comfortable: "0px" }, institucional: { compact: "0px", comfortable: "0px" }, moderno: { compact: "8px", comfortable: "12px" } } },
    { css: "--fu-size-dropzone-min", valores: { fluent: { compact: "96px", comfortable: "136px" }, institucional: { compact: "88px", comfortable: "120px" }, moderno: { compact: "120px", comfortable: "168px" } } },
    { css: "--fu-size-icon-file", valores: { fluent: { compact: "20px", comfortable: "24px" }, institucional: { compact: "20px", comfortable: "20px" }, moderno: { compact: "24px", comfortable: "32px" } } },
    { css: "--fu-size-icon-action", valores: { fluent: { compact: "16px", comfortable: "20px" }, institucional: { compact: "16px", comfortable: "16px" }, moderno: { compact: "20px", comfortable: "20px" } } },
    { css: "--fu-size-progress", valores: { fluent: "4px", institucional: "3px", moderno: "6px" } },
    { css: "--fu-size-chip", valores: { fluent: "20px", institucional: "20px", moderno: "24px" } },
    { css: "--fu-size-chip-icon", valores: { fluent: "12px", institucional: "12px", moderno: "14px" } },
    { css: "--fu-size-panel", valores: { fluent: "360px", institucional: "360px", moderno: "400px" } },
    { css: "--fu-size-hit-min", valores: { fluent: "44px", institucional: "44px", moderno: "44px" } },
    { css: "--fu-motion-fast", valores: { fluent: "100ms", institucional: "100ms", moderno: "150ms" } },
    { css: "--fu-motion-normal", valores: { fluent: "200ms", institucional: "150ms", moderno: "250ms" } },
    { css: "--fu-motion-easing", valores: { fluent: "cubic-bezier(0.33, 0, 0.67, 1)", institucional: "cubic-bezier(0.33, 0, 0.67, 1)", moderno: "cubic-bezier(0.33, 0, 0.67, 1)" } },
];

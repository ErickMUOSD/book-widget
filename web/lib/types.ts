export type Level = "niebla" | "pista" | "peligro";
export const LEVELS: { id: Level; icon: string; label: string; hint: string }[] = [
  { id: "niebla", icon: "☁️", label: "Niebla", hint: "Solo atmósfera" },
  { id: "pista", icon: "🕯️", label: "Pista", hint: "Un objeto o lugar clave" },
  { id: "peligro", icon: "🔥", label: "Peligro", hint: "Insinúa un giro" },
];

export interface Theme {
  id: string;
  name: string;
  bg: string;
  card: string;
  border: string;
  text: string;
  accent: string;
  base_palette: string[];
}

export interface SpriteInfo {
  id: number;
  name: string;
  subject?: string;
  chapter: number;
  frame_count: number;
  frame_urls: string[];
  fallback: boolean;
  created_at?: string;
}

export type BookStatus = "pending" | "ready" | "unknown" | "error";

export interface WidgetState {
  book_id: number;
  title: string;
  author: string;
  genre: string;
  theme: Theme;
  status: BookStatus;
  status_detail: string;
  current_chapter: number;
  total_chapters: number;
  next_chapter: number | null;
  finished: boolean;
  spoiler_level: Level;
  sealed: boolean;
  is_active: boolean;
  caption: string | null;
  sprite: SpriteInfo | null;
  updated_at: string;
}

export interface BookSummary {
  id: number;
  title: string;
  author: string;
  genre: string;
  theme: Theme;
  synopsis: string;
  total_chapters: number;
  status: BookStatus;
  status_detail: string;
  is_active: boolean;
  current_chapter: number;
  spoiler_level: Level;
  sealed: boolean;
}

export interface BookDetail extends BookSummary {
  chapters: { number: number; title: string }[];
  spoilers_ready: number;
  spoilers_expected: number;
}

export const ELEMENT_TYPES = ["objeto", "animal", "criatura", "personaje", "lugar"] as const;

export interface SceneElement {
  tipo: string;
  nombre: string;
  detalle: string;
}

export interface Scene {
  nudo: string;
  desenlace: string;
  elementos: SceneElement[];
}

export interface SpoilerRow {
  chapter: number;
  niebla?: string;
  pista?: string;
  peligro?: string;
  escena?: Scene;
}

export interface DeviceRow {
  id: number;
  name: string;
  created_at: string;
}

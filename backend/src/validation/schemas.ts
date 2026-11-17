import { z } from "zod";

export const AiMessageSchema = z
  .object({
    role: z.enum(["user", "assistant", "system"]),
    content: z.string().min(1).max(16_000),
  })
  .strict();

export const AiChatRequestSchema = z
  .object({
    provider: z.enum(["gemini", "openrouter", "nvidia", "groq"]),
    model: z
      .string()
      .min(2)
      .max(100)
      .regex(/^[a-zA-Z0-9._:/-]+$/, "Invalid model identifier"),
    messages: z.array(AiMessageSchema).min(1).max(32),
    temperature: z.number().min(0).max(2).optional().default(0.7),
    maxTokens: z.number().int().min(16).max(8192).optional().default(1024),
  })
  .strict();

export const InspectUrlRequestSchema = z
  .object({
    url: z.string().url().max(2048),
  })
  .strict();

export const RevokeDeviceRequestSchema = z
  .object({
    deviceId: z
      .string()
      .min(4)
      .max(128)
      .regex(/^[a-zA-Z0-9._-]+$/),
  })
  .strict();

export const CodeRunRequestSchema = z
  .object({
    language: z.enum(["javascript", "json_transform", "math"]),
    code: z.string().min(1).max(8192),
    inputJson: z.string().max(16_384).optional(),
    timeoutMs: z.number().int().min(50).max(1500).optional().default(500),
  })
  .strict();

export type AiChatRequest = z.infer<typeof AiChatRequestSchema>;
export type CodeRunRequest = z.infer<typeof CodeRunRequestSchema>;

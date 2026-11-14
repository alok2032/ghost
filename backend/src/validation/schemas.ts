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

export type AiChatRequest = z.infer<typeof AiChatRequestSchema>;

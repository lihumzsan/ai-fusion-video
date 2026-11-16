import { uploadMedia } from "@/lib/api/media";
import { uploadFile } from "@/lib/api/storage";
import type { SimpleAttachmentMediaType } from "./generation-types";

export const H3_REFERENCE_AUDIO_ACCEPT = ".mp3,.wav,audio/mpeg,audio/wav,audio/x-wav";

export async function uploadGenerationReference(
  file: File,
  mediaType: SimpleAttachmentMediaType,
): Promise<string> {
  if (mediaType === "audio") {
    const media = await uploadMedia(file, { requestId: crypto.randomUUID() });
    return `media://${media.id}`;
  }
  const folder = mediaType === "image" ? "generation-references" : "generation-video-references";
  return uploadFile(file, folder);
}

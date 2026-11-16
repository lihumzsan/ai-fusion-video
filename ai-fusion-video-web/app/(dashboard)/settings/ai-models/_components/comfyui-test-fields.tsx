"use client";

import { useId, useRef, useState } from "react";
import { Upload } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import { Label } from "@/components/ui/label";
import { getApiErrorMessage } from "@/lib/api/api-error";
import { uploadMedia } from "@/lib/api/media";
import { uploadFile } from "@/lib/api/storage";
import { comfyUiTestInput, type ComfyUiTestField } from "@/lib/comfyui-test-request";
const labels: Record<string, string> = {
  prompt: "提示词",
  negativePrompt: "负面提示词",
  width: "宽度",
  height: "高度",
  seed: "随机种子",
  count: "数量",
  duration: "时长",
  ratio: "画面比例",
  referenceImages: "参考图片",
  referenceAudios: "参考音频",
  referenceImageUrls: "参考图片",
  firstFrameUrl: "首帧",
  lastFrameUrl: "尾帧",
};

export function ComfyUiTestFields({
  fields,
  values,
  disabled,
  onChange,
  onAppend,
  onUploading,
}: {
  fields: ComfyUiTestField[];
  values: Record<string, string>;
  disabled: boolean;
  onChange: (field: string, value: string) => void;
  onAppend: (field: string, references: string[]) => void;
  onUploading: (field: string, uploading: boolean) => void;
}) {
  return (
    <div className="grid min-w-0 gap-4 md:grid-cols-2">
      {fields.map((field) => (
        <TestField
          key={field.name}
          field={field}
          value={values[field.name] ?? ""}
          disabled={disabled}
          onChange={(value) => onChange(field.name, value)}
          onAppend={(refs) => onAppend(field.name, refs)}
          onUploading={(busy) => onUploading(field.name, busy)}
        />
      ))}
    </div>
  );
}

function TestField({
  field,
  value,
  disabled,
  onChange,
  onAppend,
  onUploading,
}: {
  field: ComfyUiTestField;
  value: string;
  disabled: boolean;
  onChange: (value: string) => void;
  onAppend: (refs: string[]) => void;
  onUploading: (uploading: boolean) => void;
}) {
  const id = useId();
  const input = useRef<HTMLInputElement>(null);
  const [uploading, setUploading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const list =
    field.type === "string_list" || field.type.startsWith("uploaded_");
  const label = labels[field.name] ?? field.name;
  const audio = field.type === "uploaded_audio";
  const uploadType = audio ? "audio" : "image";
  const uploadLabel = audio ? "上传音频" : "上传图片";
  const currentCount = list ? (comfyUiTestInput(value, field.type) as string[]).length : 0;
  const atLimit = field.maximum !== undefined && currentCount >= field.maximum;
  const upload = async (files: File[]) => {
    setUploading(true);
    onUploading(true);
    setError(null);
    try {
      if (field.maximum !== undefined && currentCount + files.length > field.maximum) {
        throw new Error(`${label}最多可添加 ${field.maximum} 项。`);
      }
      if (audio && files.some((file) => !/\.(mp3|wav)$/i.test(file.name) || file.size > 15 * 1024 * 1024)) {
        throw new Error("请选择每段不超过 15 MB 的 MP3 或 WAV 音频。");
      }
      if (!audio && (files.length > 64 || files.some((file) => !file.type.startsWith("image/") || file.size > 20 * 1024 * 1024))) {
        throw new Error("每次最多上传 64 张不超过 20 MB 的图片。");
      }
      for (const file of files) {
        if (audio) {
          const media = await uploadMedia(file, { requestId: crypto.randomUUID() });
          onAppend([`media://${media.id}`]);
        } else {
          onAppend([await uploadFile(file, "images")]);
        }
      }
    } catch (failure) {
      setError(getApiErrorMessage(failure));
    } finally {
      setUploading(false);
      onUploading(false);
    }
  };
  return (
    <div className="flex min-w-0 flex-col gap-2">
      <Label htmlFor={id}>{label}</Label>
      {list || field.name === "prompt" && field.hint ? (
        <Textarea
          id={id}
          value={value}
          disabled={disabled || uploading}
          onChange={(event) => onChange(event.target.value)}
          placeholder={audio ? "上传 MP3 或 WAV，或每行填写一项音频素材引用" : list ? "每行一项，可填写媒体引用、URL 或 Data URI" : "描述主体、动作和场景"}
          aria-describedby={field.hint ? id + "-hint" : undefined}
          aria-invalid={!!error}
        />
      ) : (
        <Input
          id={id}
          type={
            field.type === "integer" || field.type === "number"
              ? "number"
              : "text"
          }
          step={field.type === "integer" ? "1" : "any"}
          min={field.minimum}
          max={field.maximum}
          value={value}
          disabled={disabled}
          placeholder={
            field.type === "boolean"
              ? "true 或 false"
              : "留空使用工作流中保存的值"
          }
          aria-describedby={field.hint ? id + "-hint" : undefined}
          onChange={(event) => onChange(event.target.value)}
        />
      )}
      {field.hint && (
        <p id={id + "-hint"} className="text-xs text-muted-foreground">{field.hint}</p>
      )}
      {(field.type === "uploaded_image" || audio) && (
        <>
          <input
            ref={input}
            type="file"
            multiple
            accept={audio ? ".mp3,.wav" : `${uploadType}/*`}
            className="hidden"
            aria-label={`上传${label}`}
            onChange={(event) => {
              const files = Array.from(event.target.files ?? []);
              event.target.value = "";
              void upload(files);
            }}
          />
          <Button
            variant="outline"
            size="sm"
            disabled={disabled || uploading || atLimit}
            className="self-start"
            onClick={() => input.current?.click()}
          >
            <Upload />
            {uploading ? "上传中…" : uploadLabel}
          </Button>
        </>
      )}
      {error && (
        <p role="alert" className="text-xs text-destructive">
          {error}
        </p>
      )}
    </div>
  );
}

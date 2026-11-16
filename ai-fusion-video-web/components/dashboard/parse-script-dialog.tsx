"use client";

import { useEffect, useRef, useState } from "react";
import { Loader2, Sparkles } from "lucide-react";
import { useConfirm } from "@/components/ui/confirm-dialog";
import { Button } from "@/components/ui/button";
import { Select, SelectContent, SelectGroup, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Textarea } from "@/components/ui/textarea";
import { DialogScrollBody } from "@/components/shared/dialog-scroll-body";
import { Dialog, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { DialogContent } from "@/components/shared/dialog-content";
import { useCommandRequestKey } from "@/hooks/use-command-request-key";
import { scriptApi } from "@/lib/api/script";

const sourceTypes = [
  {
    value: "story_to_script",
    label: "小说 / 故事",
    description: "把叙述改编成可拍摄的场次、动作和角色对白。",
    action: "改编",
  },
  {
    value: "script_full_parse",
    label: "已有剧本",
    description: "按原文整理分集、场次和已有对白。",
    action: "拆解",
  },
] as const;

export type ScriptSourceAgent = (typeof sourceTypes)[number]["value"];

interface ParseScriptDialogProps {
  open: boolean;
  scriptId: number;
  scriptVersion: number;
  initialSource: string;
  episodeCount: number;
  sceneCount: number;
  onClose: () => void;
  onProcess: (scriptId: number, scriptVersion: number, agentType: ScriptSourceAgent) => void;
}

export function ParseScriptDialog(props: ParseScriptDialogProps) {
  return props.open ? <ParseScriptForm {...props} /> : null;
}

function ParseScriptForm({ scriptId, scriptVersion, initialSource, episodeCount, sceneCount, onClose, onProcess }: ParseScriptDialogProps) {
  const { confirm } = useConfirm();
  const [version] = useState(scriptVersion);
  const [savedSource] = useState(initialSource);
  const [rawContent, setRawContent] = useState(savedSource);
  const [agentType, setAgentType] = useState<ScriptSourceAgent | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const requests = useCommandRequestKey();
  const submitting = useRef(false);
  const sourceType = sourceTypes.find(item => item.value === agentType);
  const hasSavedSource = Boolean(savedSource.trim());
  const hasStructure = episodeCount > 0 || sceneCount > 0;
  const unchangedSource = hasSavedSource && initialSource === savedSource && rawContent === savedSource && scriptVersion === version;
  const action = sourceType?.action;
  const submitLabel = loading ? "保存中" : action ? `${unchangedSource ? "开始" : "保存并"}${action}` : "开始处理";

  useEffect(() => () => {
    submitting.current = false;
  }, []);

  const submit = async () => {
    if (submitting.current || !sourceType) {
      return;
    }
    const content = rawContent.trim();
    if (!content) {
      setError("请粘贴原文");
      return;
    }
    submitting.current = true;
    setError("");
    try {
      if (unchangedSource && !hasStructure) {
        onProcess(scriptId, version, sourceType.value);
        onClose();
        return;
      }
      if (hasSavedSource || hasStructure) {
        const accepted = await confirm({
          title: unchangedSource ? "替换现有剧本结果" : "替换原文",
          description: hasStructure
            ? `当前的 ${episodeCount} 集、${sceneCount} 个场次将被替换，原有引用将失效。已生成的媒体会保留。`
            : `将保存新原文并开始${sourceType.action}。`,
          variant: "destructive",
          confirmText: `替换并${sourceType.action}`,
        });
        if (!accepted || !submitting.current) {
          submitting.current = false;
          return;
        }
      }
      setLoading(true);
      const source = unchangedSource ? savedSource : content;
      const result = await scriptApi.replaceSource(scriptId, version, source, requests.forPayload({ scriptId, version, content: source }));
      requests.clear();
      onProcess(result.id, result.version, sourceType.value);
      onClose();
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : "保存失败，原文已保留，请重试");
      submitting.current = false;
      setLoading(false);
    }
  };

  return (
    <Dialog open onOpenChange={(open) => {
      if (!open && !loading) {
        onClose();
      }
    }}>
      <DialogContent className="max-h-[calc(100vh-2rem)] flex flex-col overflow-hidden">
        <DialogHeader className="shrink-0">
          <DialogTitle>处理剧本原文</DialogTitle>
          <DialogDescription className="sr-only">根据原文类型选择处理方式。</DialogDescription>
        </DialogHeader>
        <DialogScrollBody className="flex flex-col gap-4">
          <div className="flex min-w-0 flex-col gap-2">
            <label htmlFor="script-source-type" className="text-sm font-medium">原文类型</label>
            <Select items={sourceTypes} value={agentType} onValueChange={setAgentType} disabled={loading}>
              <SelectTrigger id="script-source-type" className="w-full" aria-describedby="script-source-type-help">
                <SelectValue placeholder="选择原文类型" />
              </SelectTrigger>
              <SelectContent>
                <SelectGroup>
                  {sourceTypes.map(item => <SelectItem key={item.value} value={item.value}>{item.label}</SelectItem>)}
                </SelectGroup>
              </SelectContent>
            </Select>
            <p id="script-source-type-help" className="text-sm text-muted-foreground">
              {sourceType?.description ?? "小说和故事需要改编，已有剧本可以直接拆解。"}
            </p>
          </div>
          <div className="flex min-w-0 flex-col gap-2">
            <label htmlFor="script-source" className="text-sm font-medium">原文</label>
            <Textarea
              id="script-source"
              value={rawContent}
              onChange={(event) => setRawContent(event.target.value)}
              disabled={loading}
              className="h-48 resize-y field-sizing-fixed sm:h-64"
              placeholder="粘贴小说、故事或剧本原文"
            />
          </div>
          {scriptVersion !== version && <p className="text-sm text-destructive">剧本已有新修改。当前原文草稿已保留，提交时将检查版本。</p>}
          {error && <p role="alert" className="text-sm text-destructive">{error}</p>}
        </DialogScrollBody>
        <DialogFooter className="shrink-0 gap-2">
          <Button variant="outline" onClick={onClose} disabled={loading}>取消</Button>
          <Button variant="ai" onClick={submit} disabled={loading || !sourceType || !rawContent.trim()}>
            {loading ? <Loader2 className="animate-spin" /> : <Sparkles />}
            {submitLabel}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

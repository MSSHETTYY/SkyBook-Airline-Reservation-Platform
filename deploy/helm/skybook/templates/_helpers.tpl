{{/* Chart name and labels -------------------------------------------------------------------- */}}

{{- define "skybook.chart" -}}
{{ .Chart.Name }}-{{ .Chart.Version | replace "+" "_" }}
{{- end -}}

{{/*
Merged settings of one service: serviceDefaults overlaid with services.<name>. Args: (list $ name)
Merged by hand (two levels deep) because sprig's mergeOverwrite ignores false/0 overrides, which would
silently turn `autoscaling.enabled: false` or `java: false` back into the default.
*/}}
{{- define "skybook.svc" -}}
{{- $root := index . 0 -}}
{{- $name := index . 1 -}}
{{- $own := index $root.Values.services $name | default dict -}}
{{- $merged := deepCopy $root.Values.serviceDefaults -}}
{{- range $key, $value := $own -}}
{{- $base := index $merged $key -}}
{{- if and (kindIs "map" $value) (kindIs "map" $base) -}}
{{- $inner := deepCopy $base -}}
{{- range $k, $v := $value }}{{ $_ := set $inner $k $v }}{{ end -}}
{{- $_ := set $merged $key $inner -}}
{{- else -}}
{{- $_ := set $merged $key $value -}}
{{- end -}}
{{- end -}}
{{- toYaml $merged -}}
{{- end -}}

{{- define "skybook.labels" -}}
{{- $root := index . 0 -}}
{{- $name := index . 1 }}
helm.sh/chart: {{ include "skybook.chart" $root }}
app.kubernetes.io/name: {{ $name }}
app.kubernetes.io/instance: {{ $root.Release.Name }}
app.kubernetes.io/part-of: skybook
app.kubernetes.io/version: {{ include "skybook.tag" $root | quote }}
app.kubernetes.io/managed-by: {{ $root.Release.Service }}
{{- end -}}

{{- define "skybook.selectorLabels" -}}
{{- $root := index . 0 -}}
{{- $name := index . 1 }}
app.kubernetes.io/name: {{ $name }}
app.kubernetes.io/instance: {{ $root.Release.Name }}
{{- end -}}

{{- define "skybook.tag" -}}
{{ .Values.global.imageTag | default .Chart.AppVersion }}
{{- end -}}

{{- define "skybook.image" -}}
{{- $root := index . 0 -}}
{{- $name := index . 1 -}}
{{ $root.Values.global.imageRegistry }}/skybook-{{ $name }}:{{ include "skybook.tag" $root }}
{{- end -}}

{{/* In-cluster URL of a service, https when it serves mTLS. Args: (list $ name) */}}
{{- define "skybook.serviceUrl" -}}
{{- $root := index . 0 -}}
{{- $name := index . 1 -}}
{{- $svc := include "skybook.svc" (list $root $name) | fromYaml -}}
{{- $scheme := ternary "https" "http" (and $root.Values.mtls.enabled $svc.java $svc.mtlsServer) -}}
{{ $scheme }}://{{ $name }}.{{ $root.Release.Namespace }}.svc:{{ $svc.port }}
{{- end -}}

{{/* Spring profiles for Java services. */}}
{{- define "skybook.springProfiles" -}}
{{- $profiles := concat (list) .Values.global.springProfiles -}}
{{- if .Values.mtls.enabled }}{{ $profiles = append $profiles "mtls" }}{{ end -}}
{{- if .Values.vault.enabled }}{{ $profiles = append $profiles "vault" }}{{ end -}}
{{- if not $profiles }}{{ $profiles = list "default" }}{{ end -}}
{{ join "," $profiles }}
{{- end -}}

{{/* Enabled services, sorted for stable output. */}}
{{- define "skybook.enabledServices" -}}
{{- $names := list -}}
{{- range $name, $own := .Values.services -}}
{{- if ne (toString (dig "enabled" true $own)) "false" }}{{ $names = append $names $name }}{{ end -}}
{{- end -}}
{{ toJson (sortAlpha $names) }}
{{- end -}}

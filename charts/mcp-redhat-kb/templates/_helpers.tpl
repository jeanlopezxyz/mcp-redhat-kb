{{/*
Expand the name of the chart.
*/}}
{{- define "mcp-redhat-kb.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Create a default fully qualified app name.
*/}}
{{- define "mcp-redhat-kb.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}

{{/*
Create chart name and version as used by the chart label.
*/}}
{{- define "mcp-redhat-kb.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Common labels
*/}}
{{- define "mcp-redhat-kb.labels" -}}
helm.sh/chart: {{ include "mcp-redhat-kb.chart" . }}
{{ include "mcp-redhat-kb.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{/*
Selector labels
*/}}
{{- define "mcp-redhat-kb.selectorLabels" -}}
app.kubernetes.io/name: {{ include "mcp-redhat-kb.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{/*
Create the name of the service account to use
*/}}
{{- define "mcp-redhat-kb.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}
{{- default (include "mcp-redhat-kb.fullname" .) .Values.serviceAccount.name }}
{{- else }}
{{- default "default" .Values.serviceAccount.name }}
{{- end }}
{{- end }}

{{/*
Create the image path
*/}}
{{- define "mcp-redhat-kb.image" -}}
{{- if eq (substr 0 7 .version) "sha256:" -}}
{{- printf "%s/%s@%s" .registry .repository .version -}}
{{- else -}}
{{- printf "%s/%s:%s" .registry .repository .version -}}
{{- end -}}
{{- end -}}

{{/*
Create the name of the secret to use for Red Hat token
*/}}
{{- define "mcp-redhat-kb.secretName" -}}
{{- if .Values.redhat.existingSecret }}
{{- .Values.redhat.existingSecret }}
{{- else }}
{{- include "mcp-redhat-kb.fullname" . }}-secrets
{{- end }}
{{- end }}

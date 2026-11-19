interface ControlClientGeneratorArtifacts {
  generatedOperationsText: string;
  generatedTypesText: string;
  manifest: {
    manifestSha256: string;
  };
  manifestText: string;
  problemSchemaText: string;
}

interface ControlClientGeneratorInternal {
  editPathPatternType(pattern: string): import("typescript").TypeNode;
  dereferenceComponentAliases(bundle: unknown): unknown;
  parseSchemaDocument(documentText: string, sourcePath: string): unknown;
}

export const _internal: ControlClientGeneratorInternal;

export function generateControlClient(options: {
  checkOnly: boolean;
}): Promise<ControlClientGeneratorArtifacts>;

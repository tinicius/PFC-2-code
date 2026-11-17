"""Funcoes compartilhadas para leitura dos resultados dos experimentos."""

from pathlib import Path

import pandas as pd


IGNORED_DIRECTORIES = {"temp", "failed_solutions"}


def carregar_resultados(result_folder, results_base=None):
    """Carrega os CSVs de uma pasta de resultados em um DataFrame.

    Parameters
    ----------
    result_folder : str or pathlib.Path
        Nome da pasta dentro de ``project/results`` ou caminho para a pasta.
    results_base : str or pathlib.Path, optional
        Diretório que contém as pastas de resultados. Quando omitido, usa
        ``project/results`` relativo a raiz do repositorio.

    Returns
    -------
    pandas.DataFrame
        Linhas dos CSVs encontrados, com as colunas ``dataset``, ``instance``
        e ``result_dir`` adicionadas.

    Raises
    ------
    FileNotFoundError
        Se a pasta de resultados nao existir.
    NotADirectoryError
        Se o caminho informado nao for uma pasta.
    ValueError
        Se a pasta informada estiver fora de ``results_base``.
    """
    result_folder = Path(result_folder)
    if results_base is None:
        results_base = Path(__file__).resolve().parents[2] / "project" / "results"
    else:
        results_base = Path(results_base).resolve()

    if result_folder.is_absolute():
        result_dir = result_folder.resolve()
    else:
        result_dir = (results_base / result_folder).resolve()

    if not result_dir.exists():
        raise FileNotFoundError(f"Pasta de resultados nao encontrada: {result_dir}")
    if not result_dir.is_dir():
        raise NotADirectoryError(f"O caminho nao e uma pasta: {result_dir}")

    try:
        result_dir.relative_to(results_base)
    except ValueError as error:
        raise ValueError(
            f"A pasta de resultados deve estar dentro de {results_base}"
        ) from error

    registros = []
    dataset_dirs = sorted(
        directory
        for directory in result_dir.iterdir()
        if directory.is_dir() and directory.name not in IGNORED_DIRECTORIES
    )

    for dataset_dir in dataset_dirs:
        for csv_file in sorted(dataset_dir.glob("*.csv")):
            try:
                dataframe = pd.read_csv(csv_file)
            except Exception as error:
                raise ValueError(f"Falha ao ler o arquivo {csv_file}: {error}") from error

            dataframe["dataset"] = dataset_dir.name
            dataframe["instance"] = csv_file.stem
            dataframe["result_dir"] = result_dir.name
            registros.append(dataframe)

    if not registros:
        return pd.DataFrame()

    return pd.concat(registros, ignore_index=True)
